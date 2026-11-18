/* Enter sends only from the authenticated site's message composer. */
(() => {
 'use strict';
 if (location.origin !== 'https://chatgpt.com' || window.__danEnterSend) return;
 window.__danEnterSend = true;
 const visible = e => e && !e.disabled && e.getAttribute('aria-disabled') !== 'true' && e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';
 let composing = false;
 document.addEventListener('compositionstart', () => { composing = true; }, true);
 document.addEventListener('compositionend', () => { composing = false; }, true);
 document.addEventListener('keydown', event => {
  if (event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey || event.isComposing || composing || event.keyCode === 229) return;
  const editor = event.target?.closest?.('textarea,[contenteditable=true],[role=textbox]');
  if (!visible(editor) || editor.readOnly || editor.getAttribute('contenteditable') === 'false' || editor.closest('aside,nav,[role=dialog]')) return;
  const hint = [editor.id,editor.getAttribute('data-testid'),editor.getAttribute('aria-label'),editor.getAttribute('placeholder'),editor.getAttribute('data-placeholder')].join(' ');
  const scope = editor.closest('form') || editor.closest('[data-testid*=composer]');
  if (!scope || !(/prompt|composer|message|messaggio|chiedi|ask|reply|rispondi|follow.up/i.test(hint) || scope.matches('[data-testid*=composer]'))) return;
  const buttons = Array.from(scope.querySelectorAll('button,[role=button]'));
  const send = buttons.filter(b => visible(b) && (/^(send-button|composer-submit-button)$/.test(b.getAttribute('data-testid') || '') || /^(send|send message|submit|invia|invia messaggio)$/i.test((b.getAttribute('aria-label') || b.getAttribute('title') || '').trim())));
  if (send.length !== 1) return;
  // Consume before the site's listener to avoid duplicate submissions.
  event.preventDefault();event.stopImmediatePropagation();
  if (!event.repeat) send[0].click();
 }, true);
})();
