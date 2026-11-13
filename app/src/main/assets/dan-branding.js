(() => {
 if (location.origin !== 'https://chatgpt.com') return;
 if (window.__danHewaBranding) { window.__danHewaBranding(); return; }
 const apply = () => {
  for (const sidebar of document.querySelectorAll('#sidebar, [data-testid="sidebar"], nav[aria-label="Chat history"]')) {
   for (const node of sidebar.querySelectorAll('a[href="/"], a[href="https://chatgpt.com/"], button')) {
    if (node.textContent.trim() !== 'ChatGPT') continue;
    const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
    let text;
    while ((text = walker.nextNode())) if (text.nodeValue.trim() === 'ChatGPT') text.nodeValue = text.nodeValue.replace('ChatGPT', 'Hewa');
    node.setAttribute('aria-label', 'Hewa');
    node.title = 'Hewa · interfaccia Dan per ChatGPT';
   }
  }
 };
 window.__danHewaBranding = apply;
 let pending = false;
 new MutationObserver(() => {
  if (!pending) { pending = true; setTimeout(() => { pending = false; apply(); }, 100); }
 }).observe(document.documentElement, {childList:true,subtree:true});
 apply();
})();
