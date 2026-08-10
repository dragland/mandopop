/**
 * Word normalization logic - handles English inflections
 * Extracted from background.js for testability
 */

export const MAX_SELECTION_LENGTH = 100;

/**
 * Normalize an English word by generating possible base forms
 * Handles plurals, past tense, gerunds, comparatives, adverbs
 * @param {string} word - The word to normalize
 * @returns {string[]|null} - Array of possible base forms, or null if invalid
 */
export function normalizeWord(word) {
  const cleaned = word.toLowerCase().trim();
  if (!cleaned || cleaned.length > MAX_SELECTION_LENGTH) return null;

  // Multi-word phrase: normalize each word independently, cartesian product
  if (cleaned.includes(' ')) {
    const words = cleaned.split(/\s+/).filter(Boolean);
    if (words.length < 2 || words.length > 3) return null;

    const perWord = words.map(w => normalizeSingleWord(w));
    return cartesian(perWord).slice(0, 20);
  }

  return normalizeSingleWord(cleaned);
}

function normalizeSingleWord(cleaned) {
  const variations = [cleaned];

  // Remove trailing punctuation
  const withoutPunct = cleaned.replace(/[.,!?;:'"]+$/, '');
  if (withoutPunct !== cleaned) variations.push(withoutPunct);

  const morphologyInputs = [...new Set([cleaned, withoutPunct])];
  for (const input of morphologyInputs) {
    addMorphologyVariations(input, variations);
  }

  return [...new Set(variations)];
}

function addMorphologyVariations(cleaned, variations) {
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
}

function cartesian(arrays) {
  let results = arrays[0].map(v => v);
  for (let i = 1; i < arrays.length; i++) {
    const next = [];
    for (const prev of results) {
      for (const val of arrays[i]) {
        next.push(prev + ' ' + val);
      }
    }
    results = next;
  }
  return results;
}

const GLOSS_PREFIX = /^(?:to|a|an|the)\s+/;
// CC-CEDICT annotates senses in parentheses — classifiers "(CL:隻|只[zhi1])", register markers
// "(dialect)", "(coll.)", "(bound form)". They qualify a gloss rather than being part of it, so
// "cat (CL:隻|只[zhi1])" has to compare equal to "cat".
const GLOSS_ANNOTATION = /\([^)]*\)/g;

/**
 * Position of the sense that is exactly [key], or Infinity if none is.
 *
 * Shared with the dictionary build, which uses it to rank candidates for a key. Sense position
 * distinguishes a word that *means* the key from one that merely mentions it.
 *
 * `requireUnqualified` additionally rejects senses that BEGIN with a parenthetical — CC-CEDICT
 * writes usage-restricted meanings that way ("(of a time etc) last" on 去), and stripping the
 * annotation manufactures an exact gloss for a word that only means it in bound compounds.
 * Trailing annotations ("cat (CL:隻|只[zhi1])") still qualify. Ranking keeps the default:
 * a qualified sense is a fine tiebreaker, just not a licence to swap words on a page.
 */
export function exactGlossRank(entry, key, { requireUnqualified = false } = {}) {
  let position = 0;
  for (const definition of entry.d) {
    for (const sense of definition.split(';')) {
      const normalized = sense
        .toLowerCase()
        .replace(GLOSS_ANNOTATION, ' ')
        .replace(/\s+/g, ' ')
        .trim()
        .replace(GLOSS_PREFIX, '')
        .replace(/[.!?]+$/, '')
        .trim();
      if (normalized === key && !(requireUnqualified && sense.trim().startsWith('('))) {
        return position;
      }
      position++;
    }
  }
  return Infinity;
}

/**
 * Lookup a word in the dictionary, trying normalized variations
 *
 * The dictionary stores entries once and indexes English keys to entry ids, so this resolves
 * ids to entry objects. Callers still receive a plain array of `{ s, p, d }` — the storage
 * format is not part of this function's contract.
 *
 * @param {string} text - The word to look up
 * @param {{ entries: Array, index: Object }} dictionary - Parsed cedict.json
 * @returns {Array|null} - Array of entries or null if not found
 */
export function lookup(text, dictionary) {
  if (!dictionary || !dictionary.entries || !dictionary.index) return null;

  const variations = normalizeWord(text);
  if (!variations) return null;

  // An inflected form can pick up a key from an incidental mention — CC-CEDICT glosses 哗 as
  // "sound used to call cats", which would otherwise beat cat -> 猫. So a variant only wins
  // outright when its top entry actually means it; otherwise keep looking and fall back.
  let fallback = null;

  for (const variant of variations) {
    const ids = dictionary.index[variant];
    if (!ids || !ids.length) continue;

    const entries = ids.map(id => dictionary.entries[id]).filter(Boolean);
    if (!entries.length) continue;

    if (exactGlossRank(entries[0], variant) !== Infinity) return entries;
    if (!fallback) fallback = entries;
  }

  return fallback;
}
