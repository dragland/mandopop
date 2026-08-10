/**
 * Known-word derivation — port of the hanzi half of android KnownWordIndex.kt.
 *
 * From the started cards' written Chinese to the set of simplified forms
 * the user has met. Exposure is the bar: a word seen in a drilled sentence
 * counts the same as one taught directly, so no source column is kept.
 *
 * Readings are not ported, which changes one corner: where Kotlin narrows
 * to entries matching the card's reading before checking for a plain
 * gloss, this scans every usable entry — a strict superset, so a word the
 * phone drops on a reading mismatch can survive here.
 */

import { candidates, segment } from './segmenter.js';

// CC-CEDICT's own labels decide what is a word. An entry whose primary
// gloss is a cross-reference, a surname, or a character component is not
// evidence the user knows a word — 丩 is a component, 花 as `Huā` is a
// surname. Only the FIRST definition string is inspected, both here and
// on android.
const CROSS_REFERENCE = /^(old |archaic )?variant of |^see (also )?\S/;
const COMPONENT = /(radical|component|stroke) \(?(in|of) Chinese characters/;

function isComponent(definition) {
  return COMPONENT.test(definition);
}

function isPlain(entry) {
  const first = entry.d?.[0];
  return first !== undefined
    && !CROSS_REFERENCE.test(first)
    && !first.startsWith('surname ');
}

function hasRealEntry(entries) {
  const usable = entries.filter((e) => e.d?.[0] !== undefined && !isComponent(e.d[0]));
  if (usable.length === 0) return false;
  return usable.some(isPlain);
}

/**
 * Index dictionary entries by simplified form, in entry-id order — the
 * JS counterpart of android's entries_simplified index.
 */
export function indexBySimplified(dictionary) {
  const bySimplified = new Map();
  for (const entry of dictionary.entries) {
    const list = bySimplified.get(entry.s);
    if (list) list.push(entry);
    else bySimplified.set(entry.s, [entry]);
  }
  return bySimplified;
}

/**
 * Derive the known-word set from started cards' written Chinese.
 *
 * @param {string[]} cardHanzi — hanzi of every started, readable card
 * @param {Map<string, Array>} bySimplified — from indexBySimplified()
 * @returns {string[]} simplified forms, deterministic order
 */
export function deriveKnownWords(cardHanzi, bySimplified) {
  const probes = new Set();
  for (const hanzi of cardHanzi) {
    for (const candidate of candidates(hanzi)) probes.add(candidate);
  }

  const vocabulary = new Set();
  for (const probe of probes) {
    if (bySimplified.has(probe)) vocabulary.add(probe);
  }
  // An empty answer over real probes is indistinguishable from a
  // dictionary that failed to load, and would replace a correct index
  // with a character-only one.
  if (probes.size > 0 && vocabulary.size === 0) {
    throw new Error('Dictionary unavailable — refusing to rebuild the word index');
  }

  const isWord = (word) => vocabulary.has(word);
  const words = new Set();
  for (const hanzi of [...cardHanzi].sort()) {
    for (const word of segment(hanzi, isWord)) words.add(word);
  }

  return [...words].filter((word) => hasRealEntry(bySimplified.get(word) ?? []));
}
