/**
 * Mandopop Background Service Worker
 * Manages dictionary (loaded once), settings, and message passing
 */

import { lookup } from './lib/normalize.js';

// IndexedDB constants
const DB_NAME = 'mandopop';
const DB_VERSION = 1;
const STORE_NAME = 'dictionary';
const DICT_KEY = 'cedict';
const DICT_VERSION_KEY = 'cedict_version';

// State
let dictionary = null;
let dictionaryLoading = null;

// Get dictionary version from extension version
function getDictVersion() {
  return chrome.runtime.getManifest().version;
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

// Read dictionary from IndexedDB cache
async function readCache() {
  let db;
  try {
    db = await openDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);
      const versionReq = store.get(DICT_VERSION_KEY);
      const dataReq = store.get(DICT_KEY);
      tx.oncomplete = () => {
        db.close();
        if (versionReq.result === getDictVersion() && dataReq.result) {
          resolve(dataReq.result);
        } else {
          resolve(null);
        }
      };
      tx.onerror = () => {
        db.close();
        reject(tx.error);
      };
    });
  } catch {
    db?.close();
    return null;
  }
}

// Write dictionary to IndexedDB cache
async function writeCache(data) {
  let db;
  try {
    db = await openDB();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    const store = tx.objectStore(STORE_NAME);
    store.put(data, DICT_KEY);
    store.put(getDictVersion(), DICT_VERSION_KEY);
    return new Promise((resolve, reject) => {
      tx.oncomplete = () => {
        db.close();
        resolve();
      };
      tx.onerror = () => {
        db.close();
        reject(tx.error);
      };
    });
  } catch (error) {
    db?.close();
    console.error('[Mandopop] Failed to write cache:', error);
  }
}

// Load dictionary (from IndexedDB cache or fetch)
async function loadDictionary() {
  if (dictionary) return dictionary;
  if (dictionaryLoading) return dictionaryLoading;

  dictionaryLoading = (async () => {
    try {
      // Try IndexedDB cache first
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

      // Cache for next cold start
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
