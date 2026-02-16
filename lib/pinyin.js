/**
 * Pinyin conversion logic - tone numbers to diacritics
 * Extracted from scripts/preprocess_cedict.cjs for testability
 */

const toneMarks = {
  'a': ['ā', 'á', 'ǎ', 'à', 'a'],
  'e': ['ē', 'é', 'ě', 'è', 'e'],
  'i': ['ī', 'í', 'ǐ', 'ì', 'i'],
  'o': ['ō', 'ó', 'ǒ', 'ò', 'o'],
  'u': ['ū', 'ú', 'ǔ', 'ù', 'u'],
  'ü': ['ǖ', 'ǘ', 'ǚ', 'ǜ', 'ü'],
  'v': ['ǖ', 'ǘ', 'ǚ', 'ǜ', 'ü']  // v is sometimes used for ü
};

/**
 * Convert numbered pinyin (ma1) to tone marks (ma)
 * Follows standard pinyin tone placement rules:
 * - 'a' and 'e' always get the tone mark
 * - In 'ou', 'o' gets the mark
 * - Otherwise, the last vowel gets it
 *
 * @param {string} pinyin - Space-separated syllables with tone numbers (e.g., "ni3 hao3")
 * @returns {string} - Pinyin with tone diacritics (e.g., "ni hao")
 */
export function numberedToToneMarks(pinyin) {
  return pinyin.split(' ').map(syllable => {
    // Extract tone number (1-5, where 5 is neutral)
    const match = syllable.match(/^(.+?)([1-5])?$/);
    if (!match) return syllable;

    let [, base, tone] = match;
    tone = parseInt(tone) || 5;

    // Replace 'v' with 'ü' first
    base = base.replace(/v/g, 'ü');

    // Find vowel to add tone mark to
    const vowels = 'aeiouü';
    let vowelIndex = -1;

    if (base.includes('a')) {
      vowelIndex = base.indexOf('a');
    } else if (base.includes('e')) {
      vowelIndex = base.indexOf('e');
    } else if (base.includes('ou')) {
      vowelIndex = base.indexOf('o');
    } else {
      // Find last vowel
      for (let i = base.length - 1; i >= 0; i--) {
        if (vowels.includes(base[i])) {
          vowelIndex = i;
          break;
        }
      }
    }

    if (vowelIndex === -1 || tone === 5) {
      return base;
    }

    const vowel = base[vowelIndex];
    if (toneMarks[vowel]) {
      const marked = toneMarks[vowel][tone - 1];
      return base.slice(0, vowelIndex) + marked + base.slice(vowelIndex + 1);
    }

    return base;
  }).join(' ');
}

const stopWords = new Set([
  'a', 'an', 'the', 'to', 'of', 'in', 'on', 'at', 'for', 'by', 'with',
  'or', 'and', 'as', 'is', 'be', 'it', 'sb', 'sth', 'esp', 'etc', 'ie',
  'eg', 'vs', 'also', 'see', 'cf', 'lit', 'fig', 'var', 'abbr', 'pr'
]);

/**
 * Extract key English words from a CEDICT definition
 * Removes parenthetical notes and filters stop words
 *
 * @param {string} definition - A single definition string
 * @returns {string[]} - Array of meaningful English words
 */
export function extractEnglishWords(definition) {
  // Remove parenthetical notes and brackets
  const cleaned = definition
    .replace(/\([^)]*\)/g, ' ')
    .replace(/\[[^\]]*\]/g, ' ')
    .replace(/\{[^}]*\}/g, ' ')
    .toLowerCase();

  // Extract words (letters, hyphens, and apostrophes)
  const words = cleaned.match(/[a-z][-'a-z]*/g) || [];

  return words.filter(w => w.length > 1 && !stopWords.has(w));
}

/**
 * Extract multi-word phrase keys from a CEDICT definition
 * Strips annotations, keeps phrases of 2-3 words, skips stop-word boundaries
 *
 * NOTE: Keep in sync with scripts/preprocess_cedict.cjs
 *
 * @param {string} definition - A single definition string
 * @returns {string[]} - Array of phrase keys
 */
export function extractPhrases(definition) {
  const cleaned = definition
    .replace(/\([^)]*\)/g, ' ')
    .replace(/\[[^\]]*\]/g, ' ')
    .replace(/\{[^}]*\}/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase();

  // Only keep phrases made of plain letters, hyphens, apostrophes, and spaces
  if (!/^[a-z][-'a-z ]*[a-z]$/.test(cleaned)) return [];

  const words = cleaned.split(' ');
  if (words.length < 2 || words.length > 3) return [];

  // Skip definitional patterns (e.g. "to steal", "a basket")
  if (stopWords.has(words[0]) || stopWords.has(words[words.length - 1])) return [];

  return [cleaned];
}
