const {chromium}=require('playwright'),fs=require('fs'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({executablePath:'/usr/bin/chromium',args:['--no-sandbox']});
 try {
  const page=await browser.newPage();
  await page.route('https://chatgpt.com/**',r=>r.fulfill({contentType:'text/html',body:'<main></main>'}));
  await page.goto('https://chatgpt.com/codex');
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/dan-enter-send.js','utf8')});
  async function setup(html){await page.evaluate(html=>{document.querySelector('main').innerHTML=html;window.clicks=0;document.querySelectorAll('button').forEach(b=>b.onclick=e=>{e.preventDefault();window.clicks++;});},html);}
  const count=()=>page.evaluate(()=>window.clicks);
  await setup('<form><textarea id="prompt-textarea"></textarea><button data-testid="send-button">Send</button></form>');
  await page.locator('textarea').fill('Ciao');await page.locator('textarea').press('Enter');assert.equal(await count(),1);assert.equal(await page.locator('textarea').inputValue(),'Ciao');
  // Repeated keydown and an extra beforeinput cannot double-send the same turn.
  await page.locator('textarea').evaluate(e=>{e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',repeat:true,bubbles:true,cancelable:true}));e.dispatchEvent(new InputEvent('beforeinput',{inputType:'insertParagraph',bubbles:true,cancelable:true}));});
  assert.equal(await count(),1);
  await setup('<section><div><div contenteditable="true" role="textbox" aria-label="Richiedi modifiche di follow-up"></div></div><button type="submit" aria-label="Send prompt">↑</button></section>');
  await page.locator('[contenteditable]').fill('Codex');await page.locator('[contenteditable]').press('Enter');assert.equal(await count(),1);
  // Android virtual keyboard path: no KeyboardEvent, only cancellable beforeinput.
  await setup('<div><textarea placeholder="Chiedi a Dan"></textarea><button type="submit">↑</button></div>');
  const canceled=await page.locator('textarea').evaluate(e=>!e.dispatchEvent(new InputEvent('beforeinput',{inputType:'insertLineBreak',bubbles:true,cancelable:true})));
  assert.equal(canceled,true);assert.equal(await count(),1);
  await setup('<form><textarea id="prompt-textarea"></textarea><button data-testid="send-button" disabled>Send</button></form>');
  await page.locator('textarea').fill('Ciao');await page.locator('textarea').press('Enter');assert.equal(await count(),0);
  await setup('<form><textarea aria-label="Search notes"></textarea><button type="submit">Search</button></form>');
  await page.locator('textarea').press('Enter');assert.equal(await count(),0);
  await setup('<div><textarea id="prompt-textarea"></textarea><button aria-label="Stop generating" type="submit">Stop</button></div>');
  await page.locator('textarea').press('Enter');assert.equal(await count(),0);
  await setup('<form><textarea id="prompt-textarea"></textarea><button data-testid="send-button">Send</button></form>');
  await page.locator('textarea').evaluate(e=>{e.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}));e.dispatchEvent(new InputEvent('beforeinput',{inputType:'insertParagraph',isComposing:true,bubbles:true,cancelable:true}));e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',isComposing:true,bubbles:true,cancelable:true}));e.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true}));});assert.equal(await count(),0);
  await page.locator('textarea').fill('Prima');await page.locator('textarea').press('Shift+Enter');assert.equal(await count(),0);assert.match(await page.locator('textarea').inputValue(),/\n/);
  console.log('Browser: physical Enter, Android beforeinput, Codex without form, rerender, duplicate suppression, Shift newline, IME, disabled/stop/search passed.');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
