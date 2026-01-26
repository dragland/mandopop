/**
 * Mandopop Content Script
 * Detects text selection, requests lookup from service worker, renders popup
 */

(function () {
  'use strict';

  // Constants
  const MAX_SELECTION_LENGTH = 100;
  const DEBOUNCE_MS = 100;
  const MAX_DISPLAY_ENTRIES = 3;
  const SPEECH_RATE = 0.85;

  // State
  let popup = null;
  let selectionTimeout = null;
  let voicesLoaded = false;
  let chineseVoice = null;
  let settings = {
    enabled: true,
    showAudio: true,
    fontSize: 24
  };

  // Load settings
  async function loadSettings() {
    try {
      const stored = await chrome.storage.sync.get(['enabled', 'showAudio', 'fontSize']);
      settings = {
        enabled: stored.enabled !== false,
        showAudio: stored.showAudio !== false,
        fontSize: stored.fontSize || 24
      };
    } catch (error) {
      console.error('[Mandopop] Failed to load settings:', error);
    }
  }

  // Listen for settings changes (fixed condition)
  chrome.storage.onChanged.addListener((changes) => {
    if ('enabled' in changes) settings.enabled = changes.enabled.newValue;
    if ('showAudio' in changes) settings.showAudio = changes.showAudio.newValue;
    if ('fontSize' in changes) {
      settings.fontSize = changes.fontSize.newValue;
      if (popup) {
        popup.style.setProperty('--mandopop-font-size', `${settings.fontSize}px`);
      }
    }
  });

  // Lookup word via service worker (no local dictionary)
  async function lookup(text) {
    try {
      const response = await chrome.runtime.sendMessage({ type: 'lookup', text });
      return response?.result || null;
    } catch (error) {
      console.error('[Mandopop] Lookup failed:', error);
      return null;
    }
  }

  // Create popup element
  function createPopup() {
    if (popup) return popup;

    popup = document.createElement('div');
    popup.id = 'mandopop-popup';
    popup.style.setProperty('--mandopop-font-size', `${settings.fontSize}px`);
    document.body.appendChild(popup);

    return popup;
  }

  // Create audio button element (DOM API, no innerHTML)
  function createAudioButton(text) {
    const btn = document.createElement('button');
    btn.className = 'mandopop-audio-btn';
    btn.dataset.text = text;
    btn.title = 'Play pronunciation';

    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');

    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', 'M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z');

    svg.appendChild(path);
    btn.appendChild(svg);
    btn.addEventListener('click', handleAudioClick);

    return btn;
  }

  // Create entry element (DOM API, no innerHTML)
  function createEntryElement(entry, showDefinitions) {
    const entryDiv = document.createElement('div');
    entryDiv.className = 'mandopop-entry';

    const contentDiv = document.createElement('div');
    contentDiv.className = 'mandopop-entry-content';

    const mainRow = document.createElement('div');
    mainRow.className = 'mandopop-main-row';

    const chineseSpan = document.createElement('span');
    chineseSpan.className = 'mandopop-chinese';
    chineseSpan.textContent = entry.s;

    const pinyinSpan = document.createElement('span');
    pinyinSpan.className = 'mandopop-pinyin';
    pinyinSpan.textContent = entry.p;

    mainRow.appendChild(chineseSpan);
    mainRow.appendChild(pinyinSpan);
    contentDiv.appendChild(mainRow);

    if (showDefinitions) {
      const defsDiv = document.createElement('div');
      defsDiv.className = 'mandopop-definitions';
      defsDiv.textContent = entry.d.slice(0, 2).join('; ');
      contentDiv.appendChild(defsDiv);
    }

    entryDiv.appendChild(contentDiv);

    if (settings.showAudio) {
      entryDiv.appendChild(createAudioButton(entry.s));
    }

    return entryDiv;
  }

  // Render popup content (DOM API, no innerHTML)
  function renderPopup(entries, x, y) {
    const popup = createPopup();

    // Clear existing content safely
    popup.replaceChildren();

    const hasMultipleEntries = entries && entries.length > 1;

    if (entries && entries.length > 0) {
      const displayEntries = entries.slice(0, MAX_DISPLAY_ENTRIES);

      for (const entry of displayEntries) {
        popup.appendChild(createEntryElement(entry, hasMultipleEntries));
      }
    } else {
      const noResult = document.createElement('div');
      noResult.className = 'mandopop-no-result';
      noResult.textContent = 'No translation found';
      popup.appendChild(noResult);
    }

    positionPopup(popup, x, y);

    popup.classList.remove('mandopop-visible');
    requestAnimationFrame(() => {
      popup.classList.add('mandopop-visible');
    });
  }

  // Position popup near selection
  function positionPopup(popup, x, y) {
    const padding = 10;
    const viewportWidth = window.innerWidth;
    const viewportHeight = window.innerHeight;

    popup.style.visibility = 'hidden';
    popup.style.display = 'block';
    popup.style.left = '0';
    popup.style.top = '0';

    const rect = popup.getBoundingClientRect();
    const popupWidth = rect.width;
    const popupHeight = rect.height;

    let left = x;
    let top = y + 10;

    if (left + popupWidth > viewportWidth - padding) {
      left = viewportWidth - popupWidth - padding;
    }
    if (left < padding) {
      left = padding;
    }

    if (top + popupHeight > viewportHeight - padding) {
      top = y - popupHeight - 10;
    }
    if (top < padding) {
      top = padding;
    }

    popup.style.left = `${left}px`;
    popup.style.top = `${top}px`;
    popup.style.visibility = 'visible';
  }

  // Hide popup
  function hidePopup() {
    if (popup) {
      popup.classList.remove('mandopop-visible');
    }
  }

  // Find best Chinese voice (prefer Taiwan)
  function findChineseVoice(voices) {
    const chineseVoices = voices.filter(v => v.lang.startsWith('zh'));

    const preferredVoices = [
      { name: 'meijia', lang: 'zh-TW' },
      { name: 'shelley', lang: 'zh-TW' },
      { name: 'sandy', lang: 'zh-TW' },
      { name: 'flo', lang: 'zh-TW' },
    ];

    for (const preferred of preferredVoices) {
      const match = chineseVoices.find(v =>
        v.name.toLowerCase().includes(preferred.name) && v.lang === preferred.lang
      );
      if (match) return match;
    }

    const twVoice = chineseVoices.find(v => v.lang === 'zh-TW');
    if (twVoice) return twVoice;

    const cnVoice = chineseVoices.find(v => v.lang === 'zh-CN');
    if (cnVoice) return cnVoice;

    return chineseVoices[0] || null;
  }

  // Pre-warm speech synthesis
  async function prewarmSpeech() {
    if (!('speechSynthesis' in window)) return;

    return new Promise((resolve) => {
      const checkVoices = () => {
        const voices = window.speechSynthesis.getVoices();
        if (voices.length > 0) {
          voicesLoaded = true;
          chineseVoice = findChineseVoice(voices);
          console.log('[Mandopop] Voice selected:', chineseVoice?.name || 'default');

          const warmup = new SpeechSynthesisUtterance('');
          warmup.volume = 0;
          window.speechSynthesis.speak(warmup);

          resolve();
        }
      };

      checkVoices();

      if (!voicesLoaded) {
        window.speechSynthesis.onvoiceschanged = () => {
          checkVoices();
        };
        setTimeout(resolve, 2000);
      }
    });
  }

  // Handle audio button click
  function handleAudioClick(event) {
    event.preventDefault();
    event.stopPropagation();

    const btn = event.currentTarget;
    const text = btn.dataset.text;

    if (!text || !('speechSynthesis' in window)) return;

    window.speechSynthesis.cancel();

    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-TW';
    utterance.rate = SPEECH_RATE;

    if (chineseVoice) {
      utterance.voice = chineseVoice;
    }

    btn.classList.add('mandopop-playing');

    utterance.onend = () => {
      btn.classList.remove('mandopop-playing');
    };

    utterance.onerror = () => {
      btn.classList.remove('mandopop-playing');
    };

    window.speechSynthesis.speak(utterance);
  }

  // Handle text selection (debounced)
  async function doSelection(event) {
    if (!settings.enabled) return;
    if (popup && popup.contains(event.target)) return;

    const selection = window.getSelection();
    const text = selection.toString().trim();

    if (!text || text.length === 0 || text.length > MAX_SELECTION_LENGTH) {
      hidePopup();
      return;
    }

    if (/^[\s\d]+$/.test(text)) {
      hidePopup();
      return;
    }

    if (/[\u4e00-\u9fff]/.test(text)) {
      hidePopup();
      return;
    }

    const range = selection.getRangeAt(0);
    const rect = range.getBoundingClientRect();

    const entries = await lookup(text);
    renderPopup(entries, rect.left, rect.bottom);
  }

  // Debounced selection handler
  function handleSelection(event) {
    clearTimeout(selectionTimeout);
    selectionTimeout = setTimeout(() => doSelection(event), DEBOUNCE_MS);
  }

  // Handle click outside
  function handleClickOutside(event) {
    if (popup && !popup.contains(event.target)) {
      hidePopup();
    }
  }

  // Handle Escape key to dismiss
  function handleKeydown(event) {
    if (event.key === 'Escape' && popup) {
      hidePopup();
    }
  }

  // Initialize
  async function init() {
    await loadSettings();
    await prewarmSpeech();

    document.addEventListener('mouseup', handleSelection);
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleKeydown);

    console.log('[Mandopop] Initialized');
  }

  init();
})();
