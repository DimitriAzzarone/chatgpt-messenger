const {chromium}=require('playwright'),fs=require('fs'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({executablePath:'/usr/bin/chromium',args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1280,height:800}});
 await page.route('https://chatgpt.com/**',r=>r.fulfill({contentType:'text/html',body:`<style>body{margin:0;background:#030d1a;color:white;font:14px Arial}#unknown-sidebar-header{position:absolute;left:10px;top:14px} main{position:absolute;left:350px;top:250px} #chat{position:absolute;left:10px;top:210px}</style><div id="unknown-sidebar-header" role="button"><div><span id="title">ChatGPT</span><span>⌄</span></div></div><a id="chat" href="/c/1">ChatGPT</a><main>ChatGPT</main>`}));
 await page.goto('https://chatgpt.com/');
 await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/dan-branding.js','utf8')});
 const cover=page.locator('#dan-hewa-sidebar-cover');
 await cover.waitFor({state:'visible'});assert.equal(await cover.innerText(),'Hewa');
 let rect=await cover.boundingBox();assert(rect.x<=10&&rect.y<=14);assert(await cover.evaluate(e=>parseFloat(getComputedStyle(e).fontSize))>=32);
 assert.equal(await page.locator('#title').innerText(),'ChatGPT');
 assert.equal(await page.locator('#chat').innerText(),'ChatGPT');assert.equal(await page.locator('main').innerText(),'ChatGPT');
 assert.equal(await cover.evaluate(e=>getComputedStyle(e).pointerEvents),'none');
 await page.locator('#unknown-sidebar-header').evaluate(e=>{e.style.top='35px';e.innerHTML='<div id="title">ChatGPT</div>'});
 await page.waitForTimeout(250);rect=await cover.boundingBox();assert(rect.y>=15&&rect.y<=35);
 await page.locator('#unknown-sidebar-header').evaluate(e=>e.style.display='none');await page.waitForTimeout(250);assert.equal(await cover.isVisible(),false);
 await page.locator('#unknown-sidebar-header').evaluate(e=>e.style.display='block');await cover.waitFor({state:'visible'});
 await page.setViewportSize({width:390,height:844});await page.waitForTimeout(250);assert.equal(await cover.isVisible(),true);
 // Attribute-only updates preserve typed content and work even without a visible sidebar.
 await page.evaluate(()=>{document.body.insertAdjacentHTML('beforeend','<textarea id="plain" placeholder="Chiedi a ChatGPT">Messaggio Chiedi a ChatGPT</textarea><div id="prompt-textarea" contenteditable="true"><p data-placeholder="Chiedi a ChatGPT"><br></p></div><input id="search" placeholder="Cerca ChatGPT">');});
 await page.waitForTimeout(250);
 assert.equal(await page.locator('#plain').getAttribute('placeholder'),'Chiedi a Dan');
 assert.equal(await page.locator('#plain').inputValue(),'Messaggio Chiedi a ChatGPT');
 assert.equal(await page.locator('#prompt-textarea p').getAttribute('data-placeholder'),'Chiedi a Dan');
 assert.equal(await page.locator('#search').getAttribute('placeholder'),'Cerca ChatGPT');
 await page.locator('#unknown-sidebar-header').evaluate(e=>e.style.display='none');
 await page.locator('#prompt-textarea').evaluate(e=>e.innerHTML='<p data-placeholder="Chiedi a ChatGPT">Testo Chiedi a ChatGPT</p>');
 await page.waitForTimeout(250);
 assert.equal(await page.locator('#prompt-textarea p').getAttribute('data-placeholder'),'Chiedi a Dan');
 assert.equal(await page.locator('#prompt-textarea').innerText(),'Testo Chiedi a ChatGPT');
 await page.evaluate(()=>document.body.insertAdjacentHTML('beforeend',`<aside id="account-sidebar"><button data-testid="accounts-profile-button"><span id="avatar">DA</span><div><span id="account-name">Dimitri Azzarone</span><span id="plan">Plus</span></div></button><a href="/c/example"><span id="conversation-name">Dimitri</span></a></aside><textarea id="alternate-prompt" placeholder="Chiedi ChatGPT"></textarea><footer id="notice">ChatGPT può commettere errori. Verifica le informazioni importanti.</footer><article><p id="message-name">Dimitri Azzarone</p><p id="message-notice">ChatGPT può commettere errori.</p></article>`));
 await page.waitForTimeout(250);
 for (const id of ['account-name','plan']) assert.equal(await page.locator('#'+id).evaluate(e=>getComputedStyle(e).visibility),'hidden');
 assert.equal(await page.locator('#avatar').isVisible(),true);
 assert.equal(await page.locator('#conversation-name').isVisible(),true);
 assert.equal(await page.locator('#message-name').isVisible(),true);
 assert.equal(await page.locator('#alternate-prompt').getAttribute('placeholder'),'Chiedi a Dan');
 assert.equal(await page.locator('#notice').innerText(),'Dan può commettere errori. Verifica le informazioni importanti.');
 assert.equal(await page.locator('#message-notice').innerText(),'ChatGPT può commettere errori.');
 // React may re-create the account labels and the composer hint after navigation.
 await page.locator('#account-name').evaluate(e=>e.outerHTML='<span id="account-name">Dimitri Azzarone</span>');
 await page.locator('#alternate-prompt').evaluate(e=>e.setAttribute('placeholder','Chiedi ChatGPT'));
 await page.waitForTimeout(250);
 assert.equal(await page.locator('#account-name').evaluate(e=>getComputedStyle(e).visibility),'hidden');
 assert.equal(await page.locator('#alternate-prompt').getAttribute('placeholder'),'Chiedi a Dan');
 // Mobile layout without aside/nav/test IDs, and an editor without role=textbox.
 await page.evaluate(()=>document.body.insertAdjacentHTML('beforeend',`<div id="mobile-account"><div id="mobile-avatar">DA</div><div><span id="mobile-name">Dimitri Azzarone</span><span id="mobile-plan">Plus</span></div></div><div id="mobile-editor" contenteditable="true"><p data-placeholder="Chiedi a ChatGPT"><br></p></div>`));
 await page.waitForTimeout(250);
 for(const id of ['mobile-name','mobile-plan']) assert.equal(await page.locator('#'+id).evaluate(e=>getComputedStyle(e).visibility),'hidden');
 assert.equal(await page.locator('#mobile-avatar').isVisible(),true);
 assert.equal(await page.locator('#mobile-editor p').getAttribute('data-placeholder'),'Chiedi a Dan');
 // Literal CSS pseudo hints and separate sibling labels need more than attribute rewriting.
 await page.evaluate(()=>document.body.insertAdjacentHTML('beforeend',`<style>#pseudo-hint:empty:before{content:"Chiedi a ChatGPT"}</style><form><div contenteditable="true" id="css-editor"><p id="pseudo-hint"></p></div></form><form><div id="sibling-editor" contenteditable="true"></div><span id="sibling-hint">Chiedi a ChatGPT</span></form>`));
 await page.waitForTimeout(250);
 assert.equal(await page.locator('#pseudo-hint').evaluate(e=>getComputedStyle(e,'::before').content),'"Chiedi a Dan"');
 assert.equal(await page.locator('#sibling-hint').innerText(),'Chiedi a Dan');
 await page.locator('#css-editor').fill('Chiedi a ChatGPT');
 await page.waitForTimeout(250);
 assert.equal(await page.locator('#css-editor').innerText(),'Chiedi a ChatGPT');
 await page.locator('#css-editor').evaluate(e=>e.innerHTML='<p id="pseudo-hint"></p>');
 await page.waitForTimeout(250);
 assert.equal(await page.locator('#pseudo-hint').evaluate(e=>getComputedStyle(e,'::before').content),'"Chiedi a Dan"');
 // Editable initial boilerplate is cleared once and replaced by a genuine hint.
 await page.evaluate(()=>document.body.insertAdjacentHTML('beforeend',`<form><textarea id="initial-value">Chiedi a ChatGPT</textarea></form><form><div id="initial-editable" contenteditable="true"><p>Chiedi a ChatGPT</p></div></form><form><textarea id="existing-draft">Vorrei chiedere a ChatGPT una cosa</textarea></form>`));
 await page.waitForTimeout(250);
 assert.equal(await page.locator('#initial-value').inputValue(),'');
 assert.equal(await page.locator('#initial-value').getAttribute('placeholder'),'Chiedi a Dan');
 assert.equal(await page.locator('#initial-editable').innerText(),'');
 assert.equal(await page.locator('#initial-editable').evaluate(e=>getComputedStyle(e,'::before').content),'"Chiedi a Dan"');
 assert.equal(await page.locator('#existing-draft').inputValue(),'Vorrei chiedere a ChatGPT una cosa');
 for(const id of ['initial-value','initial-editable']) {
  await page.locator('#'+id).fill('Chiedi a ChatGPT');
  await page.waitForTimeout(250);
  const actual=await page.locator('#'+id).evaluate(e=>'value' in e?e.value:e.textContent);
  assert.equal(actual,'Chiedi a ChatGPT','typing the same phrase must be preserved');
 }
 // A user edit that happens before the observer sees a newly added editor is protected.
 await page.evaluate(()=>{
  const form=document.createElement('form');form.innerHTML='<textarea id="fast-user"></textarea>';document.body.appendChild(form);
  const e=form.firstChild;e.value='Chiedi a ChatGPT';e.dispatchEvent(new InputEvent('input',{bubbles:true,data:e.value}));
 });
 await page.waitForTimeout(250);assert.equal(await page.locator('#fast-user').inputValue(),'Chiedi a ChatGPT');
 await page.screenshot({path:'/workspace/dan-artifacts/hewa-sidebar-cover.png'});
 await browser.close();console.log('Unknown sidebar markup, cover position, SPA rerender, hidden drawer, phone resize and preserved conversation text: passed');
})();
