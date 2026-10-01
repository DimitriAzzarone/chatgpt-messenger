(function () {
  if (window.__danMemoryObserverInstalled) return;
  window.__danMemoryObserverInstalled = true;

  const bridge = window.AndroidRadio;
  if (!bridge || !bridge.rememberMemoryPending) return;

  let pending = null;
  let timer = null;
  let candidate = '';
  let candidateSince = 0;

  try {
    const saved = window.__danMemoryPending || null;
    if (saved && saved.id && saved.text) pending = saved;
  } catch (ignored) {}

  function editor() {
    return document.querySelector('#prompt-textarea') ||
      document.querySelector('textarea[data-testid="prompt-textarea"]') ||
      document.querySelector('div[contenteditable="true"][data-testid="prompt-textarea"]');
  }

  function read(el) {
    if (!el) return '';
    return (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT'
      ? el.value : el.innerText || el.textContent || '').trim();
  }

  function norm(text) {
    return text.replace(/\s+/g, ' ').trim();
  }

  function isSend(button) {
    if (!button || button.disabled) return false;
    const label = [button.getAttribute('data-testid'), button.getAttribute('aria-label'),
      button.getAttribute('title')].join(' ').toLowerCase();
    return label.includes('send-button') || label.includes('send message') ||
      label.includes('invia messaggio') ||
      (button.type === 'submit' && !!button.closest('form'));
  }

  function captureIntent() {
    const el = editor();
    const value = read(el);
    if (!value) return;
    const original = value.split('\n\n[CONTESTO LUMINEX - PAGINA CORRENTE]')[0].trim();
    if (!original) return;
    if (pending && pending.text === original && Date.now() - pending.started < 3000) return;
    pending = { id: 'dan-' + Date.now() + '-' + Math.random().toString(36).slice(2),
      text: original, started: Date.now(), userSaved: false };
    candidate = '';
    bridge.rememberMemoryPending(pending.id, pending.text);
    schedule(300);
  }

  document.addEventListener('click', function (event) {
    const button = event.target.closest && event.target.closest('button');
    if (isSend(button)) captureIntent();
  }, true);
  document.addEventListener('submit', captureIntent, true);
  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
    const el = editor();
    if (el && (event.target === el || el.contains(event.target))) captureIntent();
  }, true);

  function chatUrl() {
    return /^https:\/\/chatgpt\.com\/(?:g\/[^/]+\/)?c\/[^/?#]+/.test(location.href)
      ? location.origin + location.pathname : '';
  }

  function turns(role) {
    return Array.from(document.querySelectorAll(
      '[data-message-author-role="' + role + '"]'));
  }

  function answerText(turn) {
    const content = turn.querySelector('[data-testid="message-content"]');
    if (content) return read(content);
    const blocks = Array.from(turn.querySelectorAll('.markdown,.prose'))
      .filter(function (block) {
        return !block.parentElement || !block.parentElement.closest('.markdown,.prose');
      });
    return blocks.length ? blocks.map(read).filter(Boolean).join('\n') : read(turn);
  }

  function generating() {
    return Array.from(document.querySelectorAll('main button')).some(function (button) {
      const label = [button.getAttribute('data-testid'), button.getAttribute('aria-label'),
        button.getAttribute('title')].join(' ').toLowerCase();
      return label.includes('stop-button') || label.includes('stop generating') ||
        label.includes('interrompi generazione');
    });
  }

  function scan() {
    if (!pending) return;
    if (Date.now() - pending.started > 10 * 60 * 1000) {
      bridge.clearMemoryPending(pending.id);
      pending = null;
      return;
    }
    const url = chatUrl();
    if (!url) return;
    const users = turns('user');
    const user = users.reverse().find(function (turn) {
      return norm(read(turn)).startsWith(norm(pending.text).slice(0, 500));
    });
    if (!user) return;
    if (!pending.userSaved) {
      bridge.recordMemoryTurn(url, 'user', pending.id + ':user', pending.text);
      pending.userSaved = true;
    }
    const assistants = turns('assistant');
    const answer = assistants.reverse().find(function (turn) {
      return !!(user.compareDocumentPosition(turn) & Node.DOCUMENT_POSITION_FOLLOWING);
    });
    if (!answer) return;
    const value = answerText(answer);
    if (!value || generating()) {
      candidate = '';
      return;
    }
    if (value !== candidate) {
      candidate = value;
      candidateSince = Date.now();
      schedule(2500);
      return;
    }
    if (Date.now() - candidateSince < 2200) return;
    bridge.recordMemoryTurn(url, 'assistant', pending.id + ':assistant', value);
    bridge.clearMemoryPending(pending.id);
    pending = null;
    candidate = '';
  }

  function schedule(delay) {
    clearTimeout(timer);
    timer = setTimeout(scan, delay);
  }
  new MutationObserver(function () { schedule(500); }).observe(document.documentElement,
    { childList: true, subtree: true, characterData: true });
  setInterval(scan, 2000);
  scan();
})();
