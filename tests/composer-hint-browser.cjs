const {chromium}=require('playwright'),fs=require('fs'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({executablePath:'/usr/bin/chromium',args:['--no-sandbox']});
 try{
  const page=await browser.newPage({viewport:{width:1900,height:1000}});
  await page.route('https://chatgpt.com/**',r=>r.fulfill({contentType:'text/html',body:`<style>body{background:#030d1a;color:white}main{margin:260px 200px}textarea,[contenteditable]{width:520px;min-height:24px;font:16px Arial;background:#071428;color:white;padding:4px;border:0} #prompt-textarea:before{content:"Chiedi a ChatGPT"!important}</style><main><form><div id="prompt-textarea" contenteditable="true"><p><br></p></div><button type="button">Send</button></form><article>Chiedi a ChatGPT</article></main>`}));
  await page.goto('https://chatgpt.com/');
  for(const file of ['dan-branding.js','dan-composer-hint.js','dan-enter-send.js'])await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/'+file,'utf8')});
  const overlay=page.locator('[data-dan-owned-hint]');await overlay.waitFor({state:'visible'});
  assert.equal(await overlay.innerText(),'Chiedi a Dan');
  assert.equal((await page.locator('#prompt-textarea').innerText()).trim(),'');
  assert.equal(await page.locator('#prompt-textarea').evaluate(e=>getComputedStyle(e,'::before').content),'none');
  assert.equal(await overlay.evaluate(e=>getComputedStyle(e).pointerEvents),'none');
  const rect=await overlay.boundingBox();assert(rect.x>=200&&rect.width>400);
  await page.locator('#prompt-textarea').fill('Chiedi a ChatGPT');await page.waitForTimeout(100);assert.equal(await overlay.isVisible(),false);
  assert.equal(await page.locator('#prompt-textarea').innerText(),'Chiedi a ChatGPT');assert.equal(await page.locator('article').innerText(),'Chiedi a ChatGPT');
  await page.locator('#prompt-textarea').fill('');await overlay.waitFor({state:'visible'});
  // React recreates the field and restores the original placeholder repeatedly.
  await page.evaluate(()=>document.querySelector('form').innerHTML='<textarea id="prompt-textarea" placeholder="Chiedi a ChatGPT"></textarea><button>Send</button>');
  await page.waitForTimeout(150);assert.equal(await page.locator('[data-dan-owned-hint]:visible').count(),1);
  for(let i=0;i<3;i++){await page.locator('textarea').evaluate(e=>e.setAttribute('placeholder','Chiedi a ChatGPT'));await page.waitForTimeout(100);}
  assert.equal(await page.locator('[data-dan-owned-hint]:visible').innerText(),'Chiedi a Dan');
  const painted=await page.locator('textarea').evaluate(e=>getComputedStyle(e,'::placeholder'));assert.equal(painted.opacity,'0');
  await page.setViewportSize({width:900,height:600});await page.waitForTimeout(150);
  assert.equal(await page.locator('[data-dan-owned-hint]:visible').count(),1);
  await page.screenshot({path:'/workspace/dan-artifacts/composer-hint-15513.png'});
  await page.evaluate(()=>{const b=document.querySelector('form button');b.setAttribute('type','submit');window.sent=0;document.querySelector('form').addEventListener('submit',e=>{e.preventDefault();window.sent++;});});
  await page.locator('textarea').fill('Test reale');assert.equal(await page.locator('[data-dan-owned-hint]:visible').count(),0);assert.equal(await page.locator('textarea').inputValue(),'Test reale');
  await page.locator('textarea').press('Enter');assert.equal(await page.evaluate(()=>window.sent),1);
  console.log('Dan hint: empty contenteditable/textarea, CSS priority, React resets, resize, typed phrase and article preservation passed.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
