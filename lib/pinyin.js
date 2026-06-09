/**
 * Pinyin conversion logic - tone numbers to diacritics
 * Shared by dictionary preprocessing and unit tests.
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

    // Normalize umlaut notations to 'ü'. CC-CEDICT writes ü as 'u:' (e.g.
    // 'lu:e4'); 'v' is an input alias also seen in some data. Both are
    // lowercase-only on purpose: uppercase 'V' is a letter in initialisms
    // like 'DVD', and 'U:' never occurs (ü never starts a syllable).
    base = base.replace(/u:/g, 'ü').replace(/v/g, 'ü');

    if (tone === 5) return base;

    // Find the vowel that takes the tone mark. Search case-insensitively so
    // capitalized syllables (proper nouns, e.g. 'Er4', 'Ou1') are handled.
    const vowels = 'aeiouü';
    const lower = base.toLowerCase();
    let vowelIndex = -1;

    if (lower.includes('a')) {
      vowelIndex = lower.indexOf('a');
    } else if (lower.includes('e')) {
      vowelIndex = lower.indexOf('e');
    } else if (lower.includes('ou')) {
      vowelIndex = lower.indexOf('o');
    } else {
      // Find last vowel
      for (let i = lower.length - 1; i >= 0; i--) {
        if (vowels.includes(lower[i])) {
          vowelIndex = i;
          break;
        }
      }
    }

    if (vowelIndex === -1) return base;

    const lowerVowel = lower[vowelIndex];
    const marks = toneMarks[lowerVowel];
    if (!marks) return base;

    let marked = marks[tone - 1];
    // Preserve the original case of the marked vowel ('Er4' -> 'Èr').
    // All pinyin tone-marked vowels have precomposed uppercase forms.
    if (base[vowelIndex] !== lowerVowel) marked = marked.toUpperCase();

    return base.slice(0, vowelIndex) + marked + base.slice(vowelIndex + 1);
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
