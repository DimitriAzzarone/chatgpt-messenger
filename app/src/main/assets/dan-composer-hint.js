/* Paint a Dan-owned empty composer hint without inserting text into the editor. */
(() => {
 'use strict';
 if(location.origin!=='https://chatgpt.com'||window.__danComposerHint)return;
 window.__danComposerHint=true;
 const selector='textarea,[contenteditable=true],[role=textbox]';
 const hints=new Map(),paints=new WeakMap();
 const css='html body :is(#prompt-textarea,[data-dan-composer-empty])[data-dan-composer-empty]::before,html body :is(#prompt-textarea,[data-dan-composer-empty])[data-dan-composer-empty]::after,html body :is(#prompt-textarea,[data-dan-composer-empty])[data-dan-composer-empty] *::before,html body :is(#prompt-textarea,[data-dan-composer-empty])[data-dan-composer-empty] *::after{content:none!important}html body textarea[data-dan-composer-empty]::placeholder{color:transparent!important;-webkit-text-fill-color:transparent!important;opacity:0!important}';
 let pending=false;
 function isComposer(e){
  if(e.closest('article,[data-message-author-role],aside,nav,[role=dialog]')||e.disabled||e.readOnly)return false;
  if(e.matches('#prompt-textarea,[data-testid=prompt-textarea]'))return true;
  const label=['placeholder','data-placeholder','aria-placeholder','aria-label'].map(a=>e.getAttribute(a)||'').join(' ');
  return /(?:chied[io]\s+(?:a\s+)?|ask\s+|message\s+)(?:chatgpt|dan)\b/i.test(label);
 }
 function hide(editor){
  const hint=hints.get(editor);if(hint&&hint.style.display!=='none')hint.style.display='none';
  if(editor.hasAttribute('data-dan-composer-empty'))editor.removeAttribute('data-dan-composer-empty');
 }
 function refresh(){
  if(!document.body)return;
  let style=document.getElementById('dan-owned-composer-hint-style');
  if(!style){style=document.createElement('style');style.id='dan-owned-composer-hint-style';style.textContent=css;(document.head||document.documentElement).appendChild(style);}
  for(const [editor,hint] of hints)if(!editor.isConnected){hint.remove();hints.delete(editor);}
  for(const editor of document.querySelectorAll(selector)){
   if(!isComposer(editor)){hide(editor);continue;}
   const text=('value' in editor?editor.value:editor.textContent)||'';
   const rect=editor.getBoundingClientRect(),computed=getComputedStyle(editor);
   const hidden=document.hidden||!editor.getClientRects().length||computed.visibility==='hidden'||computed.display==='none'||editor.closest('[hidden],[inert],[aria-hidden=true]');
   if(text.replace(/[\u200b-\u200d\ufeff]/g,'').trim()||hidden||rect.width<20||rect.height<10){hide(editor);continue;}
   let hint=hints.get(editor);
   if(!hint){hint=document.createElement('span');hint.setAttribute('data-dan-owned-hint','');hint.setAttribute('aria-hidden','true');hint.textContent='Chiedi a Dan';document.body.appendChild(hint);hints.set(editor,hint);}
   const px=v=>parseFloat(v)||0;
   const left=rect.left+px(computed.borderLeftWidth)+px(computed.paddingLeft);
   const top=rect.top+px(computed.borderTopWidth)+px(computed.paddingTop);
   const width=Math.max(0,rect.width-(left-rect.left)-px(computed.paddingRight)-px(computed.borderRightWidth));
   const height=Math.min(rect.height-(top-rect.top),px(computed.lineHeight)||px(computed.fontSize)*1.4||24);
   const background=computed.backgroundColor==='rgba(0, 0, 0, 0)'?'#071428':computed.backgroundColor;
   const paint=`position:fixed!important;pointer-events:none!important;user-select:none!important;z-index:2147483645!important;box-sizing:border-box!important;overflow:hidden!important;white-space:nowrap!important;margin:0!important;padding:0!important;border:0!important;display:block;left:${left}px!important;top:${top}px!important;width:${width}px!important;height:${height}px!important;font-family:${computed.fontFamily}!important;font-size:${computed.fontSize}!important;font-weight:${computed.fontWeight}!important;line-height:${height}px!important;text-align:${computed.direction==='rtl'?'right':'left'}!important;color:#9ebed2!important;-webkit-text-fill-color:#9ebed2!important;background:${background}!important;`;
   if(paints.get(hint)!==paint||hint.style.display==='none'){hint.style.cssText=paint;paints.set(hint,paint);}
   if(!editor.hasAttribute('data-dan-composer-empty'))editor.setAttribute('data-dan-composer-empty','');
  }
 }
 function schedule(){if(!pending){pending=true;requestAnimationFrame(()=>{pending=false;refresh();});}}
 function start(){
  const observer=new MutationObserver(records=>{
   if(records.some(r=>!r.target.closest?.('[data-dan-owned-hint]')))schedule();
  });
  observer.observe(document.documentElement,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['class','style','hidden','aria-hidden','placeholder','data-placeholder']});
  refresh();setInterval(()=>{if(!document.hidden)schedule();},500);
 }
 for(const name of ['beforeinput','compositionstart'])document.addEventListener(name,event=>{const e=event.target.closest?.(selector);if(e)hide(e);},true);
 document.addEventListener('input',refresh,true);
 addEventListener('scroll',schedule,true);addEventListener('resize',schedule);document.addEventListener('visibilitychange',schedule);
 if(document.documentElement)start();else document.addEventListener('DOMContentLoaded',start,{once:true});
})();
