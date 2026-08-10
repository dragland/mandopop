/**
 * Card content extraction — port of the hanzi half of android CardParser.kt.
 *
 * The extension only needs the written form: readings and glosses for
 * display come from cedict.json, so the pinyin-alignment and English
 * halves of the Kotlin parser are deliberately not ported.
 *
 * A layout is chosen by which fields a card HAS, never by its template
 * name — the course has 21 templates over 55,460 cards and three are named
 * by meaningless slug while being ordinary vocabulary cards. Names locate
 * the field pair; shape decides which is which, because MSLK cards have
 * Chinese and Pinyin swapped on 130 of 160 sampled cards.
 */

import { hasHan, hanRuns, stripMarkup } from './chinese_text.js';

// Bump on ANY extraction change: rows below this version are stale, not
// done, and the next sync re-reads them. Without it a parse failure is
// cached as a permanent negative. Independent of android's
// CardParser.VERSION — the two parsers version their own caches.
export const PARSER_VERSION = 1;

// Order is load-bearing: MB Sentence carries both `Sentence` and `Word`,
// and the sentence layout must win — storing the whole sentence beats the
// highlighted word by 1,203 distinct words across the course.
// `word: true` layouts carry a single headword plus incidental hanzi
// (mnemonic props, image paths), so only the first Han run is content.
const LAYOUTS = [
  { written: ['HANZI'], spoken: ['PINYIN'], word: true },
  { written: ['Characters'], spoken: ['Pinyin'], word: false },
  { written: ['Chinese', 'Chinese Phrase'], spoken: ['Pinyin', 'Chinese'], word: false },
  { written: ['Sentence'], spoken: [], word: false },
  { written: ['WORD', 'Word'], spoken: ['PINYIN', 'Pinyin'], word: true },
  { written: ['COMPONENT'], spoken: [], word: true },
];

// Sound-only cards (ACTOR, SET, Minimal Pairs) are implicitly excluded:
// they carry none of the written field names, so no layout claims them and
// there is no generic fallback to scrape mnemonic hanzi out of.

function field(doc, keys) {
  for (const key of keys) {
    const value = doc.fields.get(key.toLowerCase());
    if (value !== undefined && value.trim() !== '') return value;
  }
  return null;
}

/**
 * Extract the written Chinese from a card document, or null when the card
 * carries none (sound-only templates, unrecognised shapes, blank fields).
 *
 * @param {{ fields: Map<string, string> }} doc — content fields, keys lowercased
 * @returns {string|null}
 */
export function parseHanzi(doc) {
  const layout = LAYOUTS.find((l) => field(doc, l.written) !== null);
  if (!layout) return null;

  const first = field(doc, layout.written);
  const second = field(doc, layout.spoken);

  // Whichever of the pair carries Han characters is the Chinese — tested
  // on the raw value, then re-tested after stripping, because markup can
  // be the only Han-free part (a bare image path) or hide all the Han.
  const raw = [first, second].find((v) => v !== null && hasHan(v));
  if (raw === undefined) return null;

  const written = stripMarkup(raw);
  if (!hasHan(written)) return null;

  return layout.word ? hanRuns(written)[0] : written;
}
