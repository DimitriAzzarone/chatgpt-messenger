const {chromium}=require('playwright'),fs=require('fs'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({executablePath:'/usr/bin/chromium',args:['--no-sandbox']});
 try{
  const page=await browser.newPage();
  await page.route('https://chatgpt.com/**',r=>r.fulfill({contentType:'text/html',body:'<main>Download test</main>'}));
  await page.addInitScript({content:fs.readFileSync('app/src/main/assets/dan-file-download.js','utf8')});
  await page.goto('https://chatgpt.com/');
  await page.evaluate(()=>{window.results={chunks:[],finish:null,error:null};window.DanFileDownload={append:(token,data)=>{window.results.chunks.push(atob(data));return token==='active';},finish:(...args)=>{window.results.finish=args;},fail:(token,error)=>{window.results.error=error;}};});
  const result=await page.evaluate(async()=>{
   const text='<!doctype html><title>Piano DAN</title><p>Attività, caffè, memoria, ☀️</p>'+'x'.repeat(120000);
   const blob=new Blob([text],{type:'text/html'}),url=URL.createObjectURL(blob);
   URL.revokeObjectURL(url);
   let failed=false;try{await fetch(url);}catch(_){failed=true;}
   const saved=await window.__danFileDownloads.save(url,'active','Piano_DAN.html','text/html');
   const bytes=Uint8Array.from(window.results.chunks.join(''),c=>c.charCodeAt(0));
   return {failed,saved,restored:new TextDecoder().decode(bytes),text,size:blob.size,...window.results};
  });
  assert.equal(result.failed,true,'reproduce the original failed fetch after revocation');assert.equal(result.saved,true);assert.equal(result.restored,result.text);assert.equal(result.chunks.length,3);assert.deepEqual(result.finish,['active',result.size,'Piano_DAN.html','text/html']);assert.equal(result.error,null);
  // Non-revoked URLs created without the hook still use the original fetch fallback.
  await page.evaluate(()=>{window.results={chunks:[],finish:null,error:null};});
  const fallback=await page.evaluate(async()=>{const url=URL.createObjectURL(new Blob(['fallback']));window.__danFileDownloads.take(url);const ok=await window.__danFileDownloads.save(url,'active','fallback.txt','text/plain');URL.revokeObjectURL(url);return {ok,...window.results};});
  assert.equal(fallback.ok,true);assert.equal(fallback.chunks.join(''),'fallback');
  // A same-origin iframe can create/revoke a Blob before the top WebView callback.
  await page.evaluate(()=>{window.results={chunks:[],finish:null,error:null};const iframe=document.createElement('iframe');iframe.src='/download-frame';document.body.appendChild(iframe);});
  const frame=await page.waitForEvent('framenavigated',f=>f.url().includes('download-frame'));
  const frameUrl=await frame.evaluate(()=>{const url=URL.createObjectURL(new Blob(['iframe-content']));URL.revokeObjectURL(url);return url;});
  const shared=await page.evaluate(async url=>{const ok=await window.__danFileDownloads.save(url,'active','iframe.txt','text/plain');return {ok,...window.results};},frameUrl);
  assert.equal(shared.ok,true);assert.equal(shared.chunks.join(''),'iframe-content');
  await page.evaluate(()=>{window.results={chunks:[],finish:null,error:null};window.DanFileDownload.append=()=>false;});
  const canceled=await page.evaluate(async()=>{const url=URL.createObjectURL(new Blob(['cancel']));URL.revokeObjectURL(url);const ok=await window.__danFileDownloads.save(url,'wrong','x.txt','text/plain');return {ok,...window.results};});
  assert.equal(canceled.ok,false);assert.equal(canceled.finish,null);assert.match(canceled.error,/interrotta/);
  console.log('Downloads: reproduces revoked-blob Failed to fetch; cached transfer preserves UTF-8, chunks, size/name/MIME; fallback and cancellation passed.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
