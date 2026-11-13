const {chromium}=require('playwright'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const artifacts=process.env.DAN_ARTIFACT_DIR||'/tmp/dan-artifacts';fs.mkdirSync(artifacts,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 const css=fs.readFileSync('app/src/main/assets/dan-hud.css','utf8');
 for(const [width,height] of [[390,844],[1280,800]])for(const colorScheme of ['light','dark']){
  const page=await browser.newPage({viewport:{width,height},colorScheme});
  await page.setContent(`<style>${css}</style><header>平和 HEIWA EDITION</header><main><article><div data-message-author-role="assistant" style="color:black"><p style="color:#111"><span id="answer" style="color:rgb(0,0,0)">Risposta leggibile su tablet e telefono</span></p><pre><code><span style="color:#ffcc00" id="syntax">const parola = 'ciao';</span></code></pre></div></article><form><textarea placeholder="Scrivi" id="prompt">Testo del messaggio</textarea></form></main><div role="dialog"><div style="color:black" id="dialog">Impostazioni vocali</div><button id="close">Chiudi</button></div>`);
  const colors=await page.evaluate(()=>Object.fromEntries(['answer','prompt','dialog','syntax'].map(id=>{const s=getComputedStyle(document.getElementById(id));return [id,{color:s.color,fill:s.webkitTextFillColor,bg:s.backgroundColor}]})));
  assert.equal(colors.answer.color,'rgb(224, 244, 255)');assert.equal(colors.prompt.color,'rgb(224, 244, 255)');assert.equal(colors.dialog.color,'rgb(224, 244, 255)');assert.equal(colors.syntax.color,'rgb(255, 204, 0)');
  if(width===1280&&colorScheme==='dark')await page.screenshot({path:path.join(artifacts,'1462-theme-tablet.png')});
  await page.close();
 }
 await browser.close();console.log('Chromium theme checks passed on phone/tablet viewports, in both system colour schemes.');
})().catch(e=>{console.error(e);process.exitCode=1;});
