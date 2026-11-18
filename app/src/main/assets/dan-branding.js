(() => {
 if (location.origin !== 'https://chatgpt.com') return;
 if (window.__danHewaBranding) { window.__danHewaBranding(); return; }
 // Cover only the visible sidebar title, without rewriting React-owned text nodes.
 const overlay = document.createElement('span');
 overlay.id = 'dan-hewa-sidebar-cover';
 overlay.textContent = 'Heiwa';
 overlay.setAttribute('aria-hidden', 'true');
 overlay.style.cssText = 'position:fixed!important;z-index:2147483646!important;pointer-events:none!important;display:none;box-sizing:border-box!important;overflow:hidden!important;white-space:nowrap!important;background:#030d1a!important;color:#00c8ff!important;';
 let current = null;
 const findTitle = () => {
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let text;
  while ((text = walker.nextNode())) {
   if (!['ChatGPT', 'Dan Hewa', 'Hewa', 'Dan Heiwa', 'Heiwa', 'Dan Eiwa', 'Eiwa'].includes(text.nodeValue.trim())) continue;
   const element = text.parentElement;
   if (!element || element.closest('#dan-hewa-sidebar-cover, article, [data-message-author-role], textarea, [contenteditable="true"]')) continue;
   const link = element.closest('a');
   if (link && /\/(c|g|project)\//.test(link.getAttribute('href') || '')) continue;
   const range = document.createRange(); range.selectNodeContents(text);
   const rect = range.getBoundingClientRect();
   if (rect.width <= 0 || rect.height <= 0 || rect.left < 0 || rect.left > Math.min(320, innerWidth * .55) || rect.top < 0 || rect.top > 160) continue;
   const style = getComputedStyle(element);
   if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0) continue;
   const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
   if (!hit || !(element.contains(hit) || hit.contains(element))) continue;
   return {element,rect,style,label:text.nodeValue.trim()};
  }
  return null;
 };
 const settledEditors=new WeakSet();
 const editorSelector='textarea, #prompt-textarea, [contenteditable]:not([contenteditable="false"]), .ProseMirror';
 // Mark both real typing and input supplied by dictation before the delayed observer runs.
 for(const event of ['beforeinput','input','compositionstart'])document.addEventListener(event,e=>{
  const editor=e.target?.closest?.(editorSelector);if(editor)settledEditors.add(editor);
 },true);
 const initialComposerValue = (editor,original) => {
  if(settledEditors.has(editor))return;
  if(editor.closest('article,[data-message-author-role],nav,aside'))return;
  const raw=('value' in editor ? editor.value : editor.textContent)||'';
  const clean=raw.replace(/[\u200b\u200c\u200d\ufeff]/g,'').trim();
  if(!clean)return; // Wait for a delayed initial value; input events protect user text.
  settledEditors.add(editor);
  const hint=editor.getAttribute('placeholder')||editor.getAttribute('data-placeholder')||'';
  const composer=editor.matches('#prompt-textarea,[data-testid="prompt-textarea"]')
   || editor.closest('form,[data-testid*="composer"],[class*="composer"]') || original.test(hint.trim());
  if(!composer || !original.test(clean))return;
  // Only the complete initial boilerplate is cleared, never occurrences in a draft.
  if('value' in editor){
   const win=editor.ownerDocument.defaultView;
   const proto=editor.tagName==='TEXTAREA'?win.HTMLTextAreaElement.prototype:win.HTMLInputElement.prototype;
   Object.getOwnPropertyDescriptor(proto,'value').set.call(editor,'');
   editor.setAttribute('placeholder','Chiedi a Dan');
  }else{
   const walker=document.createTreeWalker(editor,NodeFilter.SHOW_TEXT);let text;
   while((text=walker.nextNode()))text.nodeValue='';
   editor.setAttribute('data-dan-initial-hint','');
   if(getComputedStyle(editor).position==='static')editor.style.position='relative';
   if(editor.getBoundingClientRect().height<16)editor.style.minHeight='1.5em';
  }
  const win=editor.ownerDocument.defaultView;
  editor.dispatchEvent(new win.InputEvent('input',{bubbles:true,composed:true,inputType:'deleteContentBackward',data:null}));
  editor.dispatchEvent(new win.Event('change',{bubbles:true}));
 };
 const brandComposer = () => {
  const original = /^(?:Chied[io]\s+(?:a\s+)?ChatGPT|Ask\s+ChatGPT)[.\u2026!?]?$/i;
  let css=document.getElementById('dan-composer-hint-style');
  if(!css){css=document.createElement('style');css.id='dan-composer-hint-style';css.textContent='[data-dan-hint-before]::before,[data-dan-hint-after]::after{content:"Chiedi a Dan"!important}[data-dan-initial-hint][data-dan-empty]::before{content:"Chiedi a Dan"!important;position:absolute;inset-inline-start:0;top:0;pointer-events:none;color:#9ebed2}';document.head.appendChild(css);}
  for (const editor of document.querySelectorAll('textarea, #prompt-textarea, [contenteditable]:not([contenteditable="false"]), .ProseMirror')) {
   initialComposerValue(editor,original);
   const empty=!(('value' in editor ? editor.value : editor.textContent)||'').replace(/[\u200b\u200c\u200d\ufeff]/g,'').trim();
   if(editor.hasAttribute('data-dan-initial-hint')){
    if(empty&&!editor.hasAttribute('data-dan-empty'))editor.setAttribute('data-dan-empty','');
    if(!empty&&editor.hasAttribute('data-dan-empty'))editor.removeAttribute('data-dan-empty');
   }
   for (const node of [editor, ...editor.querySelectorAll('*')]) {
    for (const attr of ['placeholder', 'data-placeholder', 'aria-placeholder', 'aria-label']) {
     const value = node.getAttribute(attr);
     if (value && original.test(value.trim())) node.setAttribute(attr, 'Chiedi a Dan');
    }
    for(const side of ['before','after']) {
     const attr='data-dan-hint-'+side;
     if(!empty){if(node.hasAttribute(attr))node.removeAttribute(attr);continue;}
     const content=getComputedStyle(node,'::'+side).content;
     if(content && original.test(content.replace(/^["']|["']$/g,'').trim()) && !node.hasAttribute(attr))node.setAttribute(attr,'');
    }
   }
   // Some page versions draw the hint in a sibling label instead of an attribute.
   const scope=editor.closest('form,[data-testid*="composer"]')||editor.parentElement;
   if(!scope)continue;
   const walker=document.createTreeWalker(scope,NodeFilter.SHOW_TEXT);let text;
   while((text=walker.nextNode())){
    if(!original.test(text.nodeValue.trim()))continue;
    if(text.parentElement?.closest('textarea,[contenteditable],article,[data-message-author-role]'))continue;
    text.nodeValue=text.nodeValue.replace(/ChatGPT/i,'Dan');
   }
  }
 };
 // Only account labels in sidebar controls; retain avatar and menu access.
 const hideAccountLabels = () => {
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let text;
  while ((text = walker.nextNode())) {
   if (!/^(?:Dimitri(?: Azzarone)?|Plus)$/.test(text.nodeValue.trim())) continue;
   const element = text.parentElement;
   if (!element || element.closest('article,[data-message-author-role],textarea,[contenteditable],a[href*="/c/"]')) continue;
   let account = element.closest('[data-testid="accounts-profile-button"],[data-testid="user-menu-button"],[data-testid="profile-button"]');
   // Current mobile account rows can be plain divs, without nav/aside or test IDs.
   for (let row=element,depth=0; !account && row && depth<5; row=row.parentElement,depth++) {
    const label=(row.textContent||'').trim();
    if (/Dimitri(?: Azzarone)?/.test(label) && /Plus/.test(label) && label.length<100
        && row.getBoundingClientRect().height<=140) account=row;
   }
   if (!account || element === account || element.textContent.trim() !== text.nodeValue.trim()) continue;
   if (element.style.getPropertyValue('visibility') !== 'hidden' || element.style.getPropertyPriority('visibility') !== 'important')
    element.style.setProperty('visibility','hidden','important');
  }
 };
 const brandDisclaimer = () => {
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let text;
  while ((text = walker.nextNode())) {
   if (!/^ChatGPT (?:può commettere errori|can make mistakes)/.test(text.nodeValue.trim())) continue;
   if (text.parentElement?.closest('article,[data-message-author-role],textarea,[contenteditable="true"]')) continue;
   text.nodeValue = text.nodeValue.replace('ChatGPT','Dan');
  }
 };
 const apply = () => {
  if (!document.body) return;
  brandComposer();
  hideAccountLabels();
  brandDisclaimer();
  if (!overlay.isConnected) document.body.appendChild(overlay);
  const title = findTitle();
  if (!title) { overlay.style.display = 'none'; current = null; return; }
  current = title.element;
  const {rect,style} = title;
  overlay.textContent = /^(?:ChatGPT|Hewa|Heiwa)$/.test(title.label) ? 'Heiwa' : title.label.replace(/Hewa|Eiwa/g,'Heiwa');
  const fontSize = Math.max(32, Math.min(36, parseFloat(style.fontSize) * 1.8 || 34));
  const lineHeight = Math.ceil(fontSize * 1.25);
  overlay.style.setProperty('left', `${Math.max(0, rect.left - 1)}px`, 'important');
  overlay.style.setProperty('top', `${Math.max(0, rect.top + rect.height / 2 - lineHeight / 2)}px`, 'important');
  overlay.style.setProperty('width', `${Math.max(rect.width + 3, fontSize * (title.label === 'Dan Hewa' ? 5.4 : 3.15))}px`, 'important');
  overlay.style.setProperty('height', `${lineHeight}px`, 'important');
  overlay.style.setProperty('font-family', style.fontFamily, 'important');
  overlay.style.setProperty('font-size', `${fontSize}px`, 'important');
  overlay.style.setProperty('font-weight', '700', 'important');
  overlay.style.setProperty('line-height', `${lineHeight}px`, 'important');
  overlay.style.setProperty('text-align', 'left', 'important');
  overlay.style.setProperty('display', 'block', 'important');
 };
 window.__danHewaBranding = apply;
 let pending = false;
 const schedule = () => {
  if (!pending) { pending = true; setTimeout(() => { pending = false; apply(); }, 80); }
 };
 new MutationObserver(records => {
  if (records.some(r => r.target !== overlay && !overlay.contains(r.target))) schedule();
 }).observe(document.documentElement, {childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['class','style','hidden','aria-expanded','placeholder','data-placeholder','aria-placeholder','aria-label']});
 addEventListener('resize', schedule);
 addEventListener('scroll', schedule, true);
 // Layout movement need not mutate the label itself (drawer animations, zoom).
 setInterval(() => { if (!document.hidden) apply(); }, 750);
 apply();
})();
