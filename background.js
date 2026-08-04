/**
 * Mandopop Background Service Worker
 * Manages dictionary (loaded once), settings, and message passing
 */

import { lookup } from './lib/normalize.js';
import { DICT_HASH } from './dict_version.js';

// IndexedDB constants
const DB_NAME = 'mandopop';
const DB_VERSION = 1;
const STORE_NAME = 'dictionary';
const DICT_KEY = 'cedict';
const DICT_VERSION_KEY = 'cedict_version';

// State
let dictionary = null;
let dictionaryLoading = null;

// Cache version is the dictionary's content hash (generated alongside
// cedict.json), so regenerating the dictionary invalidates stale caches
// automatically without needing a manifest version bump.
function getDictVersion() {
  return DICT_HASH;
}

// Open IndexedDB
function openDB() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      request.result.createObjectStore(STORE_NAME);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

// Best-effort read of the cached dictionary. Resolves to the cached value, or
// null on a cache miss OR any error (corrupt DB, transaction failure) — the
// caller falls back to the bundled file. Never rejects.
async function readCache() {
  let db;
  try {
    db = await openDB();
  } catch (error) {
    console.warn('[Mandopop] Cache unavailable, will fetch:', error);
    return null;
  }
  return new Promise((resolve) => {
    const tx = db.transaction(STORE_NAME, 'readonly');
    const store = tx.objectStore(STORE_NAME);
    const versionReq = store.get(DICT_VERSION_KEY);
    const dataReq = store.get(DICT_KEY);
    tx.oncomplete = () => {
      db.close();
      const fresh = versionReq.result === getDictVersion() && dataReq.result;
      resolve(fresh ? dataReq.result : null);
    };
    tx.onerror = () => {
      db.close();
      console.warn('[Mandopop] Cache read failed, will fetch:', tx.error);
      resolve(null);
    };
  });
}

// Best-effort cache write. Logs and swallows failures — the dictionary is
// always recoverable from the bundled file, so a write failure is non-fatal.
// Never rejects.
async function writeCache(data) {
  let db;
  try {
    db = await openDB();
  } catch (error) {
    console.error('[Mandopop] Failed to write cache:', error);
    return;
  }
  return new Promise((resolve) => {
    const tx = db.transaction(STORE_NAME, 'readwrite');
    const store = tx.objectStore(STORE_NAME);
    store.put(data, DICT_KEY);
    store.put(getDictVersion(), DICT_VERSION_KEY);
    tx.oncomplete = () => {
      db.close();
      resolve();
    };
    tx.onerror = () => {
      db.close();
      console.error('[Mandopop] Failed to write cache:', tx.error);
      resolve();
    };
  });
}

// Load dictionary (from IndexedDB cache or fetch)
async function loadDictionary() {
  if (dictionary) return dictionary;
  if (dictionaryLoading) return dictionaryLoading;

  dictionaryLoading = (async () => {
    try {
      // Try IndexedDB cache first; readCache returns null (never throws) on a
      // miss or any cache error, falling through to the bundled file.
      const cached = await readCache();
      if (cached) {
        dictionary = cached;
        dictionaryLoading = null;
        console.log('[Mandopop] Dictionary loaded from IndexedDB cache');
        return dictionary;
      }

      // Fetch and parse
      const url = chrome.runtime.getURL('cedict.json');
      const response = await fetch(url);
      if (!response.ok) throw new Error(`Failed to fetch dictionary: ${response.status}`);
      dictionary = await response.json();
      console.log('[Mandopop] Dictionary loaded from fetch');

      // Cache for next cold start (fire-and-forget; writeCache self-logs).
      writeCache(dictionary);

      dictionaryLoading = null;
      return dictionary;
    } catch (error) {
      console.error('[Mandopop] Failed to load dictionary:', error);
      dictionaryLoading = null;
      return null;
    }
  })();

  return dictionaryLoading;
}

// Handle messages from content scripts
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  if (request.type === 'lookup') {
    loadDictionary().then(() => {
      const result = lookup(request.text, dictionary);
      sendResponse({ result });
    });
    return true; // Async response
  }
});

// Initialize default settings on install
chrome.runtime.onInstalled.addListener(() => {
  chrome.storage.sync.get(['showAudio', 'fontSize'], (result) => {
    const defaults = {
      showAudio: result.showAudio !== undefined ? result.showAudio : true,
      fontSize: result.fontSize !== undefined ? result.fontSize : 24
    };
    chrome.storage.sync.set(defaults);
  });
});

// Pre-load dictionary when service worker starts
loadDictionary();
