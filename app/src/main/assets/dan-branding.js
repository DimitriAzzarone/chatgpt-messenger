(() => {
 if (location.origin !== 'https://chatgpt.com') return;
 if (window.__danHewaBranding) { window.__danHewaBranding(); return; }
 // Cover only the visible sidebar title, without rewriting React-owned text nodes.
 const overlay = document.createElement('span');
 overlay.id = 'dan-hewa-sidebar-cover';
 overlay.textContent = 'Hewa';
 overlay.setAttribute('aria-hidden', 'true');
 overlay.style.cssText = 'position:fixed!important;z-index:2147483646!important;pointer-events:none!important;display:none;box-sizing:border-box!important;overflow:hidden!important;white-space:nowrap!important;background:#030d1a!important;color:#00c8ff!important;';
 let current = null;
 const findTitle = () => {
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let text;
  while ((text = walker.nextNode())) {
   if (!['ChatGPT', 'Dan Hewa', 'Hewa'].includes(text.nodeValue.trim())) continue;
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
 const brandComposer = () => {
  const original = /^(?:Chiedi\s+(?:a\s+)?ChatGPT|Ask\s+ChatGPT)$/i;
  for (const editor of document.querySelectorAll('textarea, #prompt-textarea, [contenteditable="true"][role="textbox"]')) {
   for (const node of [editor, ...editor.querySelectorAll('[data-placeholder], [placeholder]')]) {
    for (const attr of ['placeholder', 'data-placeholder', 'aria-placeholder', 'aria-label']) {
     const value = node.getAttribute(attr);
     if (value && original.test(value.trim())) node.setAttribute(attr, 'Chiedi a Dan');
    }
   }
  }
 };
 // Only account labels in sidebar controls; retain avatar and menu access.
 const hideAccountLabels = () => {
  const scopes = document.querySelectorAll('[data-testid="accounts-profile-button"], [data-testid="user-menu-button"], [data-testid="profile-button"], aside, nav, [data-testid*="sidebar"], #stage-slideover-sidebar');
  for (const scope of scopes) {
   const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT);
   let text;
   while ((text = walker.nextNode())) {
    if (!/^(?:Dimitri(?: Azzarone)?|Plus)$/.test(text.nodeValue.trim())) continue;
    const element = text.parentElement;
    if (!element || element.closest('article,[data-message-author-role],textarea,[contenteditable="true"],a[href*="/c/"]')) continue;
    const control = element.closest('button,[role="button"],footer,[data-testid="accounts-profile-button"]');
    if (!control || element === control || element.textContent.trim() !== text.nodeValue.trim()) continue;
    if (element.style.getPropertyValue('visibility') !== 'hidden' || element.style.getPropertyPriority('visibility') !== 'important')
     element.style.setProperty('visibility','hidden','important');
   }
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
  overlay.textContent = title.label === 'ChatGPT' ? 'Hewa' : title.label;
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
