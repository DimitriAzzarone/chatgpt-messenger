/* Enter sends from recognized message composers; Shift+Enter inserts a newline. */
(() => {
 'use strict';
 if (location.origin !== 'https://chatgpt.com' || window.__danEnterSend) return;
 window.__danEnterSend = true;
 const selector='textarea,[contenteditable=true],[role=textbox]';
 const visible=e=>e && !e.disabled && e.getAttribute('aria-disabled')!=='true' && e.getClientRects().length>0 && getComputedStyle(e).visibility!=='hidden';
 let composing=false, shiftEnterUntil=0;
 const sent=new WeakMap();
 document.addEventListener('compositionstart',()=>{composing=true;},true);
 document.addEventListener('compositionend',()=>{composing=false;},true);
 function targetEditor(event) {
  const target=event.composedPath?.()[0] || event.target;
  const editor=target?.closest?.(selector);
  if(!visible(editor) || editor.readOnly || editor.getAttribute('contenteditable')==='false' || editor.closest('aside,nav,[role=dialog]'))return null;
  const hint=[editor.id,...['data-testid','aria-label','placeholder','data-placeholder','aria-placeholder'].map(a=>editor.getAttribute(a))].join(' ');
  if(!/prompt|composer|message|messaggio|chied[io]|ask|reply|rispondi|follow.up/i.test(hint) && !editor.closest('[data-testid*=composer],[class*=composer]'))return null;
  return editor;
 }
 function sendFor(editor) {
  const scopes=[];
  const form=editor.closest('form');if(form)scopes.push(form);
  for(let p=editor.parentElement,depth=0;p&&depth<7;p=p.parentElement,depth++) {
   if(p.tagName==='BODY'||p.tagName==='MAIN'||p.tagName==='HTML')break;
   if(!scopes.includes(p))scopes.push(p);
  }
  for(const scope of scopes) {
   // Do not reach across another composer to find a send button.
   if(Array.from(scope.querySelectorAll(selector)).filter(visible).some(e=>e!==editor&&!editor.contains(e)&&!e.contains(editor)))continue;
   const buttons=Array.from(scope.querySelectorAll('button,[role=button]')).filter(visible);
   const named=buttons.filter(b=>{
    const id=b.getAttribute('data-testid')||'';
    const label=(b.getAttribute('aria-label')||b.getAttribute('title')||'').trim();
    return /^(?:send-button|composer-(?:submit|send)-button)$/.test(id) || /^(?:send|submit|invia)(?: (?:message|prompt|request|follow.up|messaggio|richiesta))?$/i.test(label);
   });
   if(named.length===1)return named[0];
   if(named.length>1)return null;
   const submit=buttons.filter(b=>b.getAttribute('type')==='submit'&&!/stop|interrupt|interrompi|cancel|annulla|voice|vocale/i.test((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('data-testid')||'')));
   if(submit.length===1)return submit[0];
  }
  return null;
 }
 function attempt(event) {
  const editor=targetEditor(event);if(!editor)return;
  const send=sendFor(editor);if(!send)return;
  event.preventDefault();event.stopImmediatePropagation();
  const now=Date.now();
  if(event.repeat || now-(sent.get(editor)||0)<500)return;
  sent.set(editor,now);send.click();
 }
 document.addEventListener('keydown',event=>{
  if(event.key!=='Enter')return;
  if(event.shiftKey){shiftEnterUntil=Date.now()+500;return;}
  if(event.ctrlKey||event.altKey||event.metaKey||event.isComposing||composing||event.keyCode===229)return;
  shiftEnterUntil=0;attempt(event);
 },true);
 // Android IMEs may emit beforeinput with no Enter keydown at all.
 document.addEventListener('beforeinput',event=>{
  if(!['insertParagraph','insertLineBreak'].includes(event.inputType) || !event.cancelable || event.isComposing || composing || Date.now()<shiftEnterUntil)return;
  attempt(event);
 },true);
})();
