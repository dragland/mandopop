/**
 * Han text helpers — port of android ChineseText.kt. The two must agree on
 * what counts as a Han character and how card markup is stripped, or the
 * extension derives a different known-word set than the phone.
 *
 * Supplementary-plane Han (CJK Ext B+) is deliberately excluded so string
 * indices equal character counts — which is why this uses plain UTF-16
 * indexing (.length/.slice), never [...spread].
 */

const HAN_RANGE = '一-鿿㐀-䶿㇀-㇯〇';
const HAN_CHAR = new RegExp(`[${HAN_RANGE}]`);
const HAN_RUN = new RegExp(`[${HAN_RANGE}]+`, 'g');

const HTML_TAG = /<[^>]*>/g;
const MARKDOWN_LINK = /!?\[[^\]]*\]\([^)]*\)/g;
const ESCAPE = /\\(.)/g;
const HIGHLIGHT = /==+/g;
const WHITESPACE = /[\s\u00a0\u2000-\u200b\u3000]+/g;
// Cards number duplicate prompts "在（1）" — the disambiguator is not content.
const DISAMBIGUATOR = /[（(]\s*\d+\s*[）)]\s*$/;

// Applied sequentially in this order, after tag removal — so "&amp;lt;"
// decodes in two steps to "<", matching the Kotlin replacement order.
const ENTITIES = [
  ['&nbsp;', ' '],
  ['&amp;', '&'],
  ['&lt;', '<'],
  ['&gt;', '>'],
  ['&quot;', '"'],
  ['&#39;', "'"],
];

export function hasHan(text) {
  return HAN_CHAR.test(text);
}

export function hanRuns(text) {
  return text.match(HAN_RUN) ?? [];
}

export function stripMarkup(value) {
  let t = value
    .replace(HTML_TAG, ' ')
    .replace(MARKDOWN_LINK, ' ')
    .replace(ESCAPE, '$1')
    .replace(HIGHLIGHT, '');
  for (const [entity, replacement] of ENTITIES) {
    t = t.replaceAll(entity, replacement);
  }
  return t.replace(WHITESPACE, ' ').trim();
}

export function trimPunctuation(text) {
  return text
    .replace(DISAMBIGUATOR, '')
    .replace(/^[^\p{L}\p{Nd}]+|[^\p{L}\p{Nd}]+$/gu, '');
}
