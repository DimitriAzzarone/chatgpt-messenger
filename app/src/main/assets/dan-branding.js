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
   if (text.nodeValue.trim() !== 'ChatGPT') continue;
   const element = text.parentElement;
   if (!element || element.closest('#dan-hewa-sidebar-cover, main, article, [data-message-author-role], textarea, [contenteditable="true"]')) continue;
   const link = element.closest('a');
   if (link && /\/(c|g|project)\//.test(link.getAttribute('href') || '')) continue;
   const range = document.createRange(); range.selectNodeContents(text);
   const rect = range.getBoundingClientRect();
   if (rect.width <= 0 || rect.height <= 0 || rect.left < 0 || rect.left > Math.min(320, innerWidth * .55) || rect.top < 0 || rect.top > 160) continue;
   const style = getComputedStyle(element);
   if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0) continue;
   const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
   if (!hit || !(element.contains(hit) || hit.contains(element))) continue;
   return {element,rect,style};
  }
  return null;
 };
 const apply = () => {
  if (!document.body) return;
  if (!overlay.isConnected) document.body.appendChild(overlay);
  const title = findTitle();
  if (!title) { overlay.style.display = 'none'; current = null; return; }
  current = title.element;
  const {rect,style} = title;
  overlay.style.setProperty('left', `${rect.left - 1}px`, 'important');
  overlay.style.setProperty('top', `${rect.top - 1}px`, 'important');
  overlay.style.setProperty('width', `${rect.width + 3}px`, 'important');
  overlay.style.setProperty('height', `${rect.height + 2}px`, 'important');
  overlay.style.setProperty('font', style.font, 'important');
  overlay.style.setProperty('line-height', `${rect.height}px`, 'important');
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
 }).observe(document.documentElement, {childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['class','style','hidden','aria-expanded']});
 addEventListener('resize', schedule);
 addEventListener('scroll', schedule, true);
 // Layout movement need not mutate the label itself (drawer animations, zoom).
 setInterval(() => { if (!document.hidden) apply(); }, 750);
 apply();
})();
