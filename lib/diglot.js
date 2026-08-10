/**
 * Chinglish replacement — English words the user has a known Chinese word
 * for, swapped in place on the page.
 *
 * Everything precision-critical happens ahead of time in
 * buildReplacementMap, offline, at sync time: the page pass (matchText) is
 * a dumb string match against a finished table. No text leaves the device
 * to make a replacement decision.
 */

import { normalizeWord, exactGlossRank } from './normalize.js';

// Bump on ANY change to buildReplacementMap or the known-word derivation:
// the stored map is data, and without this a rule fix waits for the next
// deck change to take effect. Same semantics as the card parser's version.
export const MAP_VERSION = 2;

/**
 * english key -> { s, p } for every dictionary key whose best candidate
 * among the user's known words actually MEANS the key.
 *
 * The bar is exactGlossRank — the same sense-position machinery rankForKey
 * uses. A known word that merely mentions the key in a late gloss must not
 * replace it: accepting any matching sense is how 门 replaces "school" and
 * 牢 replaces "fast". Index order within a key is already rankForKey
 * order, so ties on sense position fall to frequency.
 *
 * @param {Iterable<string>} knownHanzi — simplified forms the user knows
 * @param {{ entries: Array, index: Object }} dictionary — parsed cedict.json
 * @returns {Object<string, {s: string, p: string}>}
 */
export function buildReplacementMap(knownHanzi, dictionary) {
  const known = new Set(knownHanzi);
  const map = {};
  for (const [key, ids] of Object.entries(dictionary.index)) {
    let best = null;
    for (const id of ids) {
      const entry = dictionary.entries[id];
      if (!entry || !known.has(entry.s)) continue;
      const rank = exactGlossRank(entry, key, { requireUnqualified: true });
      if (rank === Infinity) continue;
      if (!best || rank < best.rank) best = { rank, entry };
    }
    if (best) map[key] = { s: best.entry.s, p: best.entry.p };
  }
  return map;
}

const WORD = /[A-Za-z][A-Za-z'’-]*/g;

/** Longest key in the map measured in words, capped at the 3 the index carries. */
export function longestKeyLength(map) {
  let longest = 1;
  for (const key of Object.keys(map)) {
    let words = 1;
    for (let i = 0; i < key.length; i++) if (key[i] === ' ') words++;
    if (words > longest) longest = words;
    if (longest >= 3) break;
  }
  return longest;
}

function isAcronym(token) {
  return token.length > 1 && /[A-Z]/.test(token) && token === token.toUpperCase();
}

/**
 * Split a text run into plain-text and replacement segments. Longest
 * phrase wins (up to the 3-word keys the index carries), inflections
 * resolve through the same normalizeWord variants lookups use, and a
 * phrase only matches across plain whitespace — never across punctuation.
 *
 * Returns null when nothing matches, so callers leave the node untouched.
 *
 * @param {string} text
 * @param {Object<string, {s: string, p: string}>} map
 * @param {number} maxPhraseTokens — pass longestKeyLength(map); trying 3-,
 *   2-, then 1-token spans triples the work for nothing on a map with no
 *   phrase keys.
 * @returns {Array<{text: string} | {original: string, s: string, p: string}>|null}
 */
export function matchText(text, map, maxPhraseTokens = 3) {
  const tokens = [];
  for (const m of text.matchAll(WORD)) {
    tokens.push({ text: m[0], start: m.index, end: m.index + m[0].length });
  }

  const segments = [];
  let matched = false;
  let cursor = 0;
  let i = 0;
  while (i < tokens.length) {
    let hit = null;
    for (let len = Math.min(maxPhraseTokens, tokens.length - i); len >= 1 && !hit; len--) {
      const span = tokens.slice(i, i + len);
      if (span.some((t) => isAcronym(t.text))) continue;
      if (span.some((t, k) => k > 0 && !/^\s+$/.test(text.slice(span[k - 1].end, t.start)))) continue;

      const variants = normalizeWord(span.map((t) => t.text).join(' '));
      // hasOwn, not `in`/indexing: the map round-trips through storage as a
      // plain object, and "constructor" is a real English word.
      const variant = variants?.find((v) => Object.hasOwn(map, v));
      if (variant) hit = { span, replacement: map[variant] };
    }

    if (hit) {
      const start = hit.span[0].start;
      const end = hit.span[hit.span.length - 1].end;
      if (start > cursor) segments.push({ text: text.slice(cursor, start) });
      segments.push({ original: text.slice(start, end), ...hit.replacement });
      matched = true;
      cursor = end;
      i += hit.span.length;
    } else {
      i += 1;
    }
  }

  if (!matched) return null;
  if (cursor < text.length) segments.push({ text: text.slice(cursor) });
  return segments;
}
