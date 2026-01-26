/**
 * Mandopop Background Service Worker
 * Manages dictionary (loaded once), settings, and message passing
 */

// Constants
const MAX_SELECTION_LENGTH = 100;

// State
let dictionary = null;
let dictionaryLoading = null;

// Load dictionary once
async function loadDictionary() {
  if (dictionary) return dictionary;
  if (dictionaryLoading) return dictionaryLoading;

  dictionaryLoading = (async () => {
    try {
      const url = chrome.runtime.getURL('cedict.json');
      const response = await fetch(url);
      dictionary = await response.json();
      console.log('[Mandopop] Dictionary loaded in service worker');
      return dictionary;
    } catch (error) {
      console.error('[Mandopop] Failed to load dictionary:', error);
      dictionaryLoading = null;
      return null;
    }
  })();

  return dictionaryLoading;
}

// Normalize word (handle inflections)
function normalizeWord(word) {
  const cleaned = word.toLowerCase().trim();
  if (!cleaned || cleaned.length > MAX_SELECTION_LENGTH) return null;

  const variations = [cleaned];

  // Remove trailing punctuation
  const withoutPunct = cleaned.replace(/[.,!?;:'"]+$/, '');
  if (withoutPunct !== cleaned) variations.push(withoutPunct);

  // -ies -> -y (studies -> study)
  if (cleaned.endsWith('ies') && cleaned.length > 4) {
    variations.push(cleaned.slice(0, -3) + 'y');
  }

  // -s / -es (cats -> cat, boxes -> box)
  if (cleaned.endsWith('s') && cleaned.length > 2) {
    variations.push(cleaned.slice(0, -1));
    if (cleaned.endsWith('es') && cleaned.length > 3) {
      variations.push(cleaned.slice(0, -2));
    }
    if (cleaned.endsWith('ses') || cleaned.endsWith('zes')) {
      variations.push(cleaned.slice(0, -2));
    }
  }

  // -ing (running -> run, making -> make)
  if (cleaned.endsWith('ing') && cleaned.length > 4) {
    const base = cleaned.slice(0, -3);
    variations.push(base);
    variations.push(base + 'e'); // making -> make
    // Doubled consonant (running -> run)
    if (base.length > 1 && base[base.length - 1] === base[base.length - 2]) {
      variations.push(base.slice(0, -1));
    }
  }

  // -ed (liked -> like, stopped -> stop)
  if (cleaned.endsWith('ed') && cleaned.length > 3) {
    variations.push(cleaned.slice(0, -2));
    variations.push(cleaned.slice(0, -1)); // liked -> like
    // Doubled consonant (stopped -> stop)
    const base = cleaned.slice(0, -2);
    if (base.length > 1 && base[base.length - 1] === base[base.length - 2]) {
      variations.push(base.slice(0, -1));
    }
  }

  // -er / -est (bigger -> big, fastest -> fast)
  if (cleaned.endsWith('er') && cleaned.length > 3) {
    variations.push(cleaned.slice(0, -2));
    variations.push(cleaned.slice(0, -1)); // nicer -> nice
    const base = cleaned.slice(0, -2);
    if (base.length > 1 && base[base.length - 1] === base[base.length - 2]) {
      variations.push(base.slice(0, -1)); // bigger -> big
    }
  }
  if (cleaned.endsWith('est') && cleaned.length > 4) {
    variations.push(cleaned.slice(0, -3));
    variations.push(cleaned.slice(0, -2)); // nicest -> nice
  }

  // -ly (quickly -> quick)
  if (cleaned.endsWith('ly') && cleaned.length > 3) {
    variations.push(cleaned.slice(0, -2));
  }

  return variations;
}

// Lookup word in dictionary
function lookup(text) {
  if (!dictionary) return null;

  const variations = normalizeWord(text);
  if (!variations) return null;

  for (const variant of variations) {
    if (dictionary[variant]) {
      return dictionary[variant];
    }
  }

  return null;
}

// Handle messages from content scripts
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.type === 'lookup') {
    loadDictionary().then(() => {
      const result = lookup(request.text);
      sendResponse({ result });
    });
    return true; // Async response
  }
});

// Initialize default settings on install
chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.sync.get(['enabled', 'showAudio', 'fontSize'], (result) => {
    const defaults = {
      enabled: result.enabled !== undefined ? result.enabled : true,
      showAudio: result.showAudio !== undefined ? result.showAudio : true,
      fontSize: result.fontSize !== undefined ? result.fontSize : 24
    };
    chrome.storage.sync.set(defaults);
  });
});

// Pre-load dictionary when service worker starts
loadDictionary();
