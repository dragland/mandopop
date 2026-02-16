import { describe, it, expect } from 'vitest';
import { numberedToToneMarks, extractEnglishWords, extractPhrases } from '../lib/pinyin.js';

describe('numberedToToneMarks', () => {
  describe('single syllables', () => {
    it('converts tone 1 (high level) - ma1 -> ma', () => {
      expect(numberedToToneMarks('ma1')).toBe('mā');
    });

    it('converts tone 2 (rising) - ma2 -> ma', () => {
      expect(numberedToToneMarks('ma2')).toBe('má');
    });

    it('converts tone 3 (falling-rising) - ma3 -> ma', () => {
      expect(numberedToToneMarks('ma3')).toBe('mǎ');
    });

    it('converts tone 4 (falling) - ma4 -> ma', () => {
      expect(numberedToToneMarks('ma4')).toBe('mà');
    });

    it('handles tone 5 (neutral) - ma5 -> ma', () => {
      expect(numberedToToneMarks('ma5')).toBe('ma');
    });

    it('handles missing tone number as neutral', () => {
      expect(numberedToToneMarks('ma')).toBe('ma');
    });
  });

  describe('tone placement rules', () => {
    it('places tone on "a" when present - bai1 -> bai', () => {
      expect(numberedToToneMarks('bai1')).toBe('bāi');
    });

    it('places tone on "e" when present - mei2 -> mei', () => {
      expect(numberedToToneMarks('mei2')).toBe('méi');
    });

    it('places tone on "o" in "ou" - gou3 -> gou', () => {
      expect(numberedToToneMarks('gou3')).toBe('gǒu');
    });

    it('places tone on last vowel otherwise - liu2 -> liu', () => {
      expect(numberedToToneMarks('liu2')).toBe('liú');
    });

    it('places tone on last vowel in "iu" - niu2 -> niu', () => {
      expect(numberedToToneMarks('niu2')).toBe('niú');
    });

    it('places tone on last vowel in "ui" - gui4 -> gui', () => {
      expect(numberedToToneMarks('gui4')).toBe('guì');
    });
  });

  describe('u-umlaut handling', () => {
    it('converts v to u-umlaut - nv3 -> nu', () => {
      expect(numberedToToneMarks('nv3')).toBe('nǚ');
    });

    it('converts lv4 -> lu', () => {
      expect(numberedToToneMarks('lv4')).toBe('lǜ');
    });

    it('handles u-umlaut with tone marks - lu:3 style input', () => {
      // The current implementation uses 'v' for u-umlaut
      expect(numberedToToneMarks('lv3')).toBe('lǚ');
    });
  });

  describe('multi-syllable words', () => {
    it('converts ni3 hao3 -> ni hao', () => {
      expect(numberedToToneMarks('ni3 hao3')).toBe('nǐ hǎo');
    });

    it('converts zhong1 guo2 -> zhong guo', () => {
      expect(numberedToToneMarks('zhong1 guo2')).toBe('zhōng guó');
    });

    it('converts xue2 xi2 -> xue xi', () => {
      expect(numberedToToneMarks('xue2 xi2')).toBe('xué xí');
    });

    it('handles mixed tones - ma1 ma2 ma3 ma4', () => {
      expect(numberedToToneMarks('ma1 ma2 ma3 ma4')).toBe('mā má mǎ mà');
    });

    it('handles neutral tones in phrases', () => {
      expect(numberedToToneMarks('ma1 ma5')).toBe('mā ma');
    });
  });

  describe('edge cases', () => {
    it('handles empty string', () => {
      expect(numberedToToneMarks('')).toBe('');
    });

    it('handles consonant-only syllables', () => {
      // Some interjections or borrowed words might not have vowels
      expect(numberedToToneMarks('m2')).toBe('m');
    });

    it('preserves syllables without tone numbers', () => {
      expect(numberedToToneMarks('de')).toBe('de');
    });
  });

  describe('common words', () => {
    it('converts shi4 (to be)', () => {
      expect(numberedToToneMarks('shi4')).toBe('shì');
    });

    it('converts bu4 (not)', () => {
      expect(numberedToToneMarks('bu4')).toBe('bù');
    });

    it('converts dui4 bu5 qi3 (sorry)', () => {
      expect(numberedToToneMarks('dui4 bu5 qi3')).toBe('duì bu qǐ');
    });

    it('converts xie4 xie5 (thank you)', () => {
      expect(numberedToToneMarks('xie4 xie5')).toBe('xiè xie');
    });

    it('converts zai4 jian4 (goodbye)', () => {
      expect(numberedToToneMarks('zai4 jian4')).toBe('zài jiàn');
    });
  });
});

describe('extractEnglishWords', () => {
  describe('basic extraction', () => {
    it('extracts simple words', () => {
      expect(extractEnglishWords('cat')).toEqual(['cat']);
    });

    it('extracts multiple words', () => {
      const result = extractEnglishWords('big cat');
      expect(result).toContain('big');
      expect(result).toContain('cat');
    });

    it('lowercases words', () => {
      expect(extractEnglishWords('CAT')).toEqual(['cat']);
    });
  });

  describe('parenthetical and bracket removal', () => {
    it('removes parenthetical notes (content inside parens is excluded)', () => {
      // Parenthetical content is removed to filter out usage notes like "(linguistics)"
      const result = extractEnglishWords('cat (domestic animal)');
      expect(result).toContain('cat');
      // Words inside parentheses are intentionally excluded
      expect(result).not.toContain('domestic');
      expect(result).not.toContain('animal');
    });

    it('removes square brackets', () => {
      const result = extractEnglishWords('cat [feline]');
      expect(result).toContain('cat');
      // Content inside brackets should be removed
      expect(result).not.toContain('feline');
    });

    it('removes curly braces', () => {
      const result = extractEnglishWords('cat {noun}');
      expect(result).toContain('cat');
      expect(result).not.toContain('noun');
    });
  });

  describe('stop word filtering', () => {
    it('filters out "a", "an", "the"', () => {
      const result = extractEnglishWords('a big cat');
      expect(result).not.toContain('a');
      expect(result).toContain('big');
      expect(result).toContain('cat');
    });

    it('filters out prepositions', () => {
      const result = extractEnglishWords('to go to the store');
      expect(result).not.toContain('to');
      expect(result).not.toContain('the');
      expect(result).toContain('go');
      expect(result).toContain('store');
    });

    it('filters out CEDICT-specific markers (sb, sth)', () => {
      const result = extractEnglishWords('to give sb sth');
      expect(result).not.toContain('sb');
      expect(result).not.toContain('sth');
      expect(result).toContain('give');
    });

    it('filters out single-letter words', () => {
      const result = extractEnglishWords('I am a cat');
      expect(result).not.toContain('I');
      expect(result).not.toContain('a');
    });
  });

  describe('hyphenated and apostrophe words', () => {
    it('preserves hyphenated words', () => {
      const result = extractEnglishWords('self-aware');
      expect(result).toContain('self-aware');
    });

    it('preserves apostrophe words', () => {
      const result = extractEnglishWords("don't");
      expect(result).toContain("don't");
    });
  });

  describe('edge cases', () => {
    it('handles empty string', () => {
      expect(extractEnglishWords('')).toEqual([]);
    });

    it('handles string with only stop words', () => {
      const result = extractEnglishWords('a an the to of');
      expect(result).toEqual([]);
    });

    it('handles string with numbers', () => {
      const result = extractEnglishWords('category 5 hurricane');
      expect(result).toContain('category');
      expect(result).toContain('hurricane');
      expect(result).not.toContain('5');
    });
  });
});

describe('extractPhrases', () => {
  it('extracts a clean 2-word phrase', () => {
    expect(extractPhrases('ice cream')).toEqual(['ice cream']);
  });

  it('extracts a 3-word phrase', () => {
    expect(extractPhrases('green chili pepper')).toEqual(['green chili pepper']);
  });

  it('strips parenthetical annotations', () => {
    expect(extractPhrases('ice cream (loanword)')).toEqual(['ice cream']);
  });

  it('strips square bracket annotations', () => {
    expect(extractPhrases('ice cream [food]')).toEqual(['ice cream']);
  });

  it('strips curly brace annotations', () => {
    expect(extractPhrases('ice cream {noun}')).toEqual(['ice cream']);
  });

  it('returns [] for single words', () => {
    expect(extractPhrases('cat')).toEqual([]);
  });

  it('returns [] for phrases over 3 words', () => {
    expect(extractPhrases('large department store chain')).toEqual([]);
  });

  it('returns [] for non-ASCII content', () => {
    expect(extractPhrases('CL:個|个[ge4]')).toEqual([]);
  });

  it('returns [] for phrases starting with a stop word', () => {
    expect(extractPhrases('to steal')).toEqual([]);
    expect(extractPhrases('a basket')).toEqual([]);
    expect(extractPhrases('the end')).toEqual([]);
  });

  it('returns [] for phrases ending with a stop word', () => {
    expect(extractPhrases('mixed in')).toEqual([]);
    expect(extractPhrases('jealous of')).toEqual([]);
  });

  it('lowercases the result', () => {
    expect(extractPhrases('Ice Cream')).toEqual(['ice cream']);
  });

  it('collapses internal whitespace', () => {
    expect(extractPhrases('ice   cream')).toEqual(['ice cream']);
  });
});
