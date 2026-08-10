/**
 * Mandopop Chinglish Content Script
 * Replaces English words the user has a known Chinese word for with hanzi.
 *
 * All decisions were made at sync time: this script matches page text
 * against a finished replacement table from storage and never sends page
 * content anywhere. Matching logic is shared with the service worker via
 * dynamic import so morphology cannot drift from lookups.
 */

(function () {
  'use strict';

  // Never rewrite text the user is editing, code, or our own UI.
  const SKIP_SELECTOR = 'script,style,noscript,textarea,input,select,code,pre,#mandopop-popup,#mandopop-weave-tip,.mandopop-diglot';
  // Forces a slice through even on pages that never go idle (rAF loops,
  // chatty SPAs) — without it the queue grows for the life of the tab.
  const IDLE_TIMEOUT_MS = 2000;
  const MIN_IDLE_MS = 4;

  let matchText = null;
  let map = null;
  let maxPhraseTokens = 3;
  let enabled = true;
  let observer = null;
  const queue = [];
  let draining = false;

  function active() {
    return enabled && map !== null;
  }

  function replaceable(node) {
    const parent = node.parentElement;
    return parent !== null && !parent.closest(SKIP_SELECTOR) && !parent.isContentEditable;
  }

  function replaceNode(node) {
    const segments = matchText(node.data, map, maxPhraseTokens);
    if (!segments) return;

    const fragment = document.createDocumentFragment();
    for (const segment of segments) {
      if (segment.s === undefined) {
        fragment.appendChild(document.createTextNode(segment.text));
        continue;
      }
      const span = document.createElement('span');
      span.className = 'mandopop-diglot';
      span.lang = 'zh-CN';
      span.textContent = segment.s;
      // No native title — its fixed ~1s hover delay is deadly in the
      // recall loop. The instant tooltip below carries the reveal.
      span.dataset.original = segment.original;
      span.dataset.pinyin = segment.p;
      fragment.appendChild(span);
    }
    node.parentNode.replaceChild(fragment, node);
  }

  function enqueue(root) {
    if (root.nodeType === Node.TEXT_NODE) {
      if (replaceable(root)) {
        queue.push(root);
        drain();
      }
      return;
    }
    if (root.nodeType !== Node.ELEMENT_NODE || root.closest(SKIP_SELECTOR)) return;

    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      if (replaceable(node)) queue.push(node);
    }
    drain();
  }

  // The initial sweep can be thousands of nodes; do it in deadline-bounded
  // idle slices so page load never stutters. The observer is disconnected
  // while we write — disconnect also discards pending records, and nothing
  // else can mutate between our synchronous writes — so our own fragments
  // are never re-enqueued and re-matched.
  function drain() {
    if (draining || queue.length === 0) return;
    draining = true;
    requestIdleCallback(function step(deadline) {
      observer?.disconnect();
      // A timed-out slice never sees idle time; grant it a small budget so
      // busy pages still make progress.
      let forced = deadline.didTimeout ? 50 : 0;
      while (queue.length > 0 && (deadline.timeRemaining() > MIN_IDLE_MS || forced-- > 0)) {
        const node = queue.shift();
        if (active() && node.isConnected && replaceable(node)) replaceNode(node);
      }
      if (observer) observe();
      if (queue.length > 0) requestIdleCallback(step, { timeout: IDLE_TIMEOUT_MS });
      else draining = false;
    }, { timeout: IDLE_TIMEOUT_MS });
  }

  function observe() {
    observer ??= new MutationObserver((mutations) => {
      if (!active()) return;
      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) enqueue(node);
      }
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  // One shared tooltip, shown instantly on hover — the reveal is the
  // feature's core loop, so it must not wait on the native title delay.
  let tip = null;

  function tooltip() {
    if (tip) return tip;
    tip = document.createElement('div');
    tip.id = 'mandopop-weave-tip';
    tip.setAttribute('role', 'tooltip');
    tip.append(document.createElement('span'), document.createElement('span'));
    tip.firstChild.className = 'mandopop-weave-tip-pinyin';
    document.body.appendChild(tip);
    return tip;
  }

  function showTip(span) {
    const t = tooltip();
    t.firstChild.textContent = span.dataset.pinyin;
    t.lastChild.textContent = ` · ${span.dataset.original}`;
    t.classList.add('mandopop-visible');
    const rect = span.getBoundingClientRect();
    const left = Math.min(Math.max(rect.left, 4), window.innerWidth - t.offsetWidth - 4);
    const below = rect.bottom + 4;
    const top = below + t.offsetHeight > window.innerHeight ? rect.top - t.offsetHeight - 4 : below;
    t.style.left = `${left}px`;
    t.style.top = `${top}px`;
  }

  function hideTip() {
    tip?.classList.remove('mandopop-visible');
  }

  document.addEventListener('mouseover', (event) => {
    const span = event.target.closest?.('.mandopop-diglot');
    if (span) showTip(span);
    else hideTip();
  });
  document.addEventListener('scroll', hideTip, { passive: true, capture: true });

  function unapply() {
    hideTip();
    for (const span of document.querySelectorAll('.mandopop-diglot')) {
      span.replaceWith(document.createTextNode(span.dataset.original));
    }
  }

  function restart() {
    observer?.disconnect();
    observer = null;
    queue.length = 0;
    unapply();
    if (active()) {
      observe();
      enqueue(document.body);
    }
  }

  let longestKeyLength = null;

  function setMap(next) {
    map = next && Object.keys(next).length > 0 ? next : null;
    maxPhraseTokens = map ? longestKeyLength(map) : 3;
  }

  async function init() {
    ({ matchText, longestKeyLength } = await import(chrome.runtime.getURL('lib/diglot.js')));

    const [{ replacementMap }, { diglotWeave }] = await Promise.all([
      chrome.storage.local.get('replacementMap'),
      chrome.storage.sync.get('diglotWeave'),
    ]);
    setMap(replacementMap);
    enabled = diglotWeave !== false;

    chrome.storage.onChanged.addListener((changes, area) => {
      if (area === 'local' && 'replacementMap' in changes) {
        setMap(changes.replacementMap.newValue);
        restart();
      }
      if (area === 'sync' && 'diglotWeave' in changes) {
        enabled = changes.diglotWeave.newValue !== false;
        restart();
      }
    });

    if (active()) {
      observe();
      enqueue(document.body);
    }
  }

  init();
})();
