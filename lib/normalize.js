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

/**
 * Lookup a word in the dictionary, trying normalized variations
 * @param {string} text - The word to look up
 * @param {Object} dictionary - Dictionary object keyed by English words
 * @returns {Array|null} - Array of entries or null if not found
 */
export function lookup(text, dictionary) {
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
