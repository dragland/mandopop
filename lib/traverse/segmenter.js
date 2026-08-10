/**
 * Sentence segmentation — port of android Segmenter.kt.
 *
 * Forward longest match against the dictionary, capped at 4 characters.
 * Segmentation invents, it does not omit: every failure mode is a real
 * dictionary word cut across an intended boundary (二十/个人 out of
 * "twenty people"). Backward and minimise-singles matching were measured
 * and rejected — each fixes ~5 cases and breaks ~60. Do not swap the
 * algorithm without re-measuring; NEVER_A_WORD_HERE handles the cuts that
 * actually occur in the course.
 */

import { hanRuns } from './chinese_text.js';

export const MAX_WORD_LENGTH = 4;

// Real CC-CEDICT entries that only ever appear across an intended boundary
// in this course. Nothing here is taught as a headword anywhere, so the
// list cannot remove a real word — and it is skipped when the whole run
// IS the word, so a card stating exactly 你好 still yields it.
const NEVER_A_WORD_HERE = new Set([
  '你妈', '妈的', '要不', '要说', '我去', '不知', '在外', '吃藕',
  '吃的', '个人', '不快', '不大', '在那儿', '那是', '你好',
]);

/**
 * Every 2..4-character substring of every Han run — the membership probe
 * set handed to the dictionary before segmenting. Single characters are
 * never membership-tested; segment() emits them unconditionally.
 */
export function candidates(text) {
  const found = new Set();
  for (const run of hanRuns(text)) {
    for (let start = 0; start < run.length; start++) {
      const last = Math.min(start + MAX_WORD_LENGTH, run.length);
      for (let end = start + 2; end <= last; end++) {
        found.add(run.slice(start, end));
      }
    }
  }
  return found;
}

/**
 * Split text into dictionary words. Non-Han characters terminate runs and
 * are never emitted. Single characters are always emitted, word or not —
 * the derivation drops the ones the dictionary doesn't know.
 *
 * @param {string} text
 * @param {(word: string) => boolean} isWord
 * @returns {string[]}
 */
export function segment(text, isWord) {
  const segments = [];
  for (const run of hanRuns(text)) {
    let start = 0;
    while (start < run.length) {
      let length = 1;
      const longest = Math.min(MAX_WORD_LENGTH, run.length - start);
      for (let candidate = longest; candidate >= 2; candidate--) {
        const word = run.slice(start, start + candidate);
        const whole = start === 0 && candidate === run.length;
        if ((whole || !NEVER_A_WORD_HERE.has(word)) && isWord(word)) {
          length = candidate;
          break;
        }
      }
      segments.push(run.slice(start, start + length));
      start += length;
    }
  }
  return segments;
}
