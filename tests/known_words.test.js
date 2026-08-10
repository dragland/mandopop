import { describe, it, expect } from 'vitest';
import { deriveKnownWords, indexBySimplified } from '../lib/traverse/known_words.js';

function dict(entries) {
  return indexBySimplified({ entries });
}

describe('deriveKnownWords', () => {
  it('segments sentences and keeps dictionary words', () => {
    const bySimplified = dict([
      { s: '他', p: 'tā', d: ['he', 'him'] },
      { s: '听不懂', p: 'tīng bù dǒng', d: ['unable to understand what one hears'] },
    ]);
    const words = deriveKnownWords(['他听不懂。'], bySimplified);
    expect(new Set(words)).toEqual(new Set(['他', '听不懂']));
  });

  it('drops single characters the dictionary does not know', () => {
    const bySimplified = dict([{ s: '东西', p: 'dōng xi', d: ['thing', 'stuff'] }]);
    expect(deriveKnownWords(['东西呀'], bySimplified)).toEqual(['东西']);
  });

  it('drops words CC-CEDICT knows only as a component or radical', () => {
    const bySimplified = dict([
      { s: '亻', p: 'rén', d: ['"person" radical in Chinese characters (Kangxi radical 9)'] },
    ]);
    expect(deriveKnownWords(['亻'], bySimplified)).toEqual([]);
  });

  it('drops words with only surname or cross-reference glosses', () => {
    const bySimplified = dict([
      { s: '花', p: 'Huā', d: ['surname Hua'] },
      { s: '蟇', p: 'má', d: ['old variant of 蟆[ma2]'] },
    ]);
    expect(deriveKnownWords(['花', '蟇'], bySimplified)).toEqual([]);
  });

  it('keeps a word when any usable entry is plain', () => {
    const bySimplified = dict([
      { s: '花', p: 'Huā', d: ['surname Hua'] },
      { s: '花', p: 'huā', d: ['flower', 'blossom'] },
    ]);
    expect(deriveKnownWords(['花'], bySimplified)).toEqual(['花']);
  });

  it('refuses to rebuild when the dictionary answers nothing', () => {
    expect(() => deriveKnownWords(['你好'], dict([]))).toThrow(/Dictionary unavailable/);
  });

  it('does not trip the guard on single-character cards, which are never probed', () => {
    expect(deriveKnownWords(['一'], dict([]))).toEqual([]);
  });
});
