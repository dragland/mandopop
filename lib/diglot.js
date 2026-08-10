/**
 * Chinglish replacement — English words the user has a known Chinese word
 * for, swapped in place on the page.
 *
 * Everything precision-critical happens ahead of time in
 * buildReplacementMap, offline, at sync time: the page pass (matchText) is
 * a dumb string match against a finished table. No text leaves the device
 * to make a replacement decision.
 */

import { normalizeWord, translatableGloss } from './normalize.js';

// Bump on ANY change to buildReplacementMap or the known-word derivation:
// the stored map is data, and without this a rule fix waits for the next
// deck change to take effect. Same semantics as the card parser's version.
export const MAP_VERSION = 6;

/**
 * english key -> { s, p }, and only for keys whose CANONICAL translation
 * the user knows.
 *
 * The canonical translation is the key's first index candidate with an
 * exact, unqualified gloss for it — index order is rankForKey order, so
 * this is exactly what a lookup would answer first. The weave never shows
 * a translation the lookup wouldn't lead with: a known word that carries
 * the key as a side-sense (花 lists "florid"), or a lesser synonym, does
 * not weave — the key stays English until the canonical word is learned.
 * Measured on the full course: keeps 80.9% of keys and kills the
 * form-vs-sense class wholesale; the strict unique-translation variant
 * measured 34.2% because it mistakes Chinese synonymy for ambiguity.
 *
 * @param {Iterable<string>} knownHanzi — simplified forms the user knows
 * @param {{ entries: Array, index: Object }} dictionary — parsed cedict.json
 * @returns {Object<string, {s: string, p: string}>}
 */
export function buildReplacementMap(knownHanzi, dictionary) {
  const known = new Set(knownHanzi);
  const map = {};
  for (const [key, ids] of Object.entries(dictionary.index)) {
    // Case-split canonicals: "march" and "March" have different canonical
    // translations (游行 vs 三月), told apart by the dictionary's own gloss
    // capitalization. Within each case the canonical is the candidate with
    // the strictly best sense position, index (frequency) order breaking
    // ties — the build's ranking treats positions 0 and 1 as one tier, so
    // trusting it verbatim lets frequency-monster 文 take "culture" from
    // 文化. Never a fallback to a lesser word the user happens to know.
    let base = null;
    let baseAt = Infinity;
    let proper = null;
    let properAt = Infinity;
    for (const id of ids) {
      const entry = dictionary.entries[id];
      if (!entry) continue;
      const gloss = translatableGloss(entry, key);
      if (gloss.base < baseAt) { base = entry; baseAt = gloss.base; }
      if (gloss.proper < properAt) { proper = entry; properAt = gloss.proper; }
    }
    const value = base !== null && known.has(base.s) ? { s: base.s, p: base.p } : null;
    const properValue = proper !== null && proper !== base && known.has(proper.s)
      ? { s: proper.s, p: proper.p }
      : null;
    if (value !== null && properValue !== null) map[key] = { ...value, proper: properValue };
    else if (value !== null) map[key] = value;
    else if (properValue !== null) map[key] = { proper: properValue };
  }
  return map;
}

const WORD = /[A-Za-z][A-Za-z'’-]*/g;

// The suffix-stripper in normalizeWord invents stems that are real English
// words — has→"ha", used→"us", being→"bee" — which lookups tolerate (the
// user sees the candidates) but an automatic swap must not. The weave
// resolves closed-class irregulars explicitly and refuses sub-3-character
// stems; everything else keeps lookup's morphology.
const IRREGULAR = new Map(Object.entries({
  am: 'be', is: 'be', are: 'be', was: 'be', were: 'be', been: 'be', being: 'be',
  has: 'have', had: 'have', having: 'have',
  does: 'do', did: 'do', done: 'do', doing: 'do',
  goes: 'go', went: 'go', gone: 'go', going: 'go',
}));

function weaveVariants(phrase) {
  const lower = phrase.toLowerCase();
  const irregular = IRREGULAR.get(lower);
  if (irregular !== undefined) return [lower, irregular];
  return normalizeWord(phrase)?.filter((v) => v === lower || v.length >= 3) ?? null;
}

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

// A neighbor counts toward a Title-Case run only across plain whitespace —
// "March. The next" is a sentence boundary, not a proper-noun run.
function neighborCapitalized(text, tokens, index, edge, before) {
  const neighbor = tokens[index];
  if (neighbor === undefined) return false;
  const gap = before ? text.slice(neighbor.end, edge.start) : text.slice(edge.end, neighbor.start);
  return /^\s+$/.test(gap) && /^[A-Z]/.test(neighbor.text);
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

      // Capitalized tokens resolve through the proper-noun canonical when
      // one exists ("March" -> 三月); lowercase tokens never do ("march"
      // -> 游行, and "june" without a lowercase gloss stays English).
      // Inside a Title-Case run — a capitalized token whose whitespace
      // neighbor is also capitalized ("Miss Bennet") — base senses never
      // apply: CC-CEDICT does not mark English titles, but the page does.
      const capitalized = /^[A-Z]/.test(span[0].text);
      const titleRun = capitalized && (
        neighborCapitalized(text, tokens, i - 1, span[0], true)
        || neighborCapitalized(text, tokens, i + len, span[span.length - 1], false)
      );
      const variants = weaveVariants(span.map((t) => t.text).join(' '));
      let replacement = null;
      // hasOwn, not `in`/indexing: the map round-trips through storage as a
      // plain object, and "constructor" is a real English word.
      variants?.find((v) => {
        if (!Object.hasOwn(map, v)) return false;
        const value = map[v];
        if (capitalized && value.proper !== undefined) replacement = value.proper;
        else if (!titleRun && value.s !== undefined) replacement = { s: value.s, p: value.p };
        return replacement !== null;
      });
      if (replacement !== null) hit = { span, replacement };
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
