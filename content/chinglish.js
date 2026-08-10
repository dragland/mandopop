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
  const SKIP_SELECTOR = 'script,style,noscript,textarea,input,select,code,pre,#mandopop-popup,.mandopop-chinglish';
  const NODES_PER_IDLE_SLICE = 200;

  let matchText = null;
  let map = null;
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
    const segments = matchText(node.data, map);
    if (!segments) return;

    const fragment = document.createDocumentFragment();
    for (const segment of segments) {
      if (segment.s === undefined) {
        fragment.appendChild(document.createTextNode(segment.text));
        continue;
      }
      const span = document.createElement('span');
      span.className = 'mandopop-chinglish';
      span.lang = 'zh-CN';
      span.textContent = segment.s;
      span.title = `${segment.p} · ${segment.original}`;
      span.dataset.original = segment.original;
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

  // The initial sweep can be thousands of nodes; do it in idle slices so
  // page load never stutters. Correctness doesn't depend on finishing —
  // every slice re-checks that its nodes are still live and eligible.
  function drain() {
    if (draining || queue.length === 0) return;
    draining = true;
    requestIdleCallback(function step() {
      let budget = NODES_PER_IDLE_SLICE;
      while (queue.length > 0 && budget-- > 0) {
        const node = queue.shift();
        if (active() && node.isConnected && replaceable(node)) replaceNode(node);
      }
      if (queue.length > 0) requestIdleCallback(step);
      else draining = false;
    });
  }

  function observe() {
    observer = new MutationObserver((mutations) => {
      if (!active()) return;
      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) enqueue(node);
      }
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  function unapply() {
    for (const span of document.querySelectorAll('.mandopop-chinglish')) {
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

  async function init() {
    ({ matchText } = await import(chrome.runtime.getURL('lib/chinglish.js')));

    const [{ replacementMap }, { chinglish }] = await Promise.all([
      chrome.storage.local.get('replacementMap'),
      chrome.storage.sync.get('chinglish'),
    ]);
    map = replacementMap && Object.keys(replacementMap).length > 0 ? replacementMap : null;
    enabled = chinglish !== false;

    chrome.storage.onChanged.addListener((changes, area) => {
      if (area === 'local' && 'replacementMap' in changes) {
        const next = changes.replacementMap.newValue;
        map = next && Object.keys(next).length > 0 ? next : null;
        restart();
      }
      if (area === 'sync' && 'chinglish' in changes) {
        enabled = changes.chinglish.newValue !== false;
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
