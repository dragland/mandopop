import { describe, it, expect } from 'vitest';
import { normalizeWord, lookup } from '../lib/normalize.js';

describe('normalizeWord', () => {
  describe('basic behavior', () => {
    it('returns original word as first variation', () => {
      const result = normalizeWord('cat');
      expect(result[0]).toBe('cat');
    });

    it('lowercases input', () => {
      const result = normalizeWord('CAT');
      expect(result).toContain('cat');
    });

    it('trims whitespace', () => {
      const result = normalizeWord('  cat  ');
      expect(result[0]).toBe('cat');
    });

    it('returns null for empty string', () => {
      expect(normalizeWord('')).toBeNull();
      expect(normalizeWord('   ')).toBeNull();
    });

    it('returns null for overly long input (>100 chars)', () => {
      const longWord = 'a'.repeat(101);
      expect(normalizeWord(longWord)).toBeNull();
    });
  });

  describe('punctuation removal', () => {
    it('removes trailing periods', () => {
      const result = normalizeWord('cat.');
      expect(result).toContain('cat');
    });

    it('removes trailing commas and other punctuation', () => {
      const result = normalizeWord('cat,');
      expect(result).toContain('cat');
    });

    it('removes multiple trailing punctuation marks', () => {
      const result = normalizeWord('cat...');
      expect(result).toContain('cat');
    });
  });

  describe('plural handling (-s, -es, -ies)', () => {
    it('handles simple -s plurals (cats -> cat)', () => {
      const result = normalizeWord('cats');
      expect(result).toContain('cat');
    });

    it('handles -es plurals (boxes -> box)', () => {
      const result = normalizeWord('boxes');
      expect(result).toContain('box');
    });

    it('handles -ses plurals (buses -> bus)', () => {
      const result = normalizeWord('buses');
      expect(result).toContain('bus');
    });

    it('handles -zes plurals (buzzes -> buzz)', () => {
      // Note: quizzes -> quiz would need doubled-consonant handling for plurals
      // Current implementation produces: quizzes -> quizze -> quizz
      const result = normalizeWord('buzzes');
      expect(result).toContain('buzz');
    });

    it('handles -ies plurals (studies -> study)', () => {
      const result = normalizeWord('studies');
      expect(result).toContain('study');
    });

    it('does not over-strip short words', () => {
      // "is" should not become "" or "i"
      const result = normalizeWord('is');
      expect(result[0]).toBe('is');
    });
  });

  describe('gerund handling (-ing)', () => {
    it('handles simple -ing (eating -> eat)', () => {
      const result = normalizeWord('eating');
      expect(result).toContain('eat');
    });

    it('handles -ing with silent e restoration (making -> make)', () => {
      const result = normalizeWord('making');
      expect(result).toContain('make');
    });

    it('handles doubled consonant (running -> run)', () => {
      const result = normalizeWord('running');
      expect(result).toContain('run');
    });

    it('handles doubled consonant (stopping -> stop)', () => {
      const result = normalizeWord('stopping');
      expect(result).toContain('stop');
    });

    it('does not strip very short -ing words', () => {
      // "sing" should stay as "sing", not become ""
      const result = normalizeWord('sing');
      expect(result[0]).toBe('sing');
    });
  });

  describe('past tense handling (-ed)', () => {
    it('handles simple -ed (walked -> walk)', () => {
      const result = normalizeWord('walked');
      expect(result).toContain('walk');
    });

    it('handles -ed with silent e (liked -> like)', () => {
      const result = normalizeWord('liked');
      expect(result).toContain('like');
    });

    it('handles doubled consonant (stopped -> stop)', () => {
      const result = normalizeWord('stopped');
      expect(result).toContain('stop');
    });

    it('handles doubled consonant (planned -> plan)', () => {
      const result = normalizeWord('planned');
      expect(result).toContain('plan');
    });
  });

  describe('comparative/superlative handling (-er, -est)', () => {
    it('handles -er comparatives (faster -> fast)', () => {
      const result = normalizeWord('faster');
      expect(result).toContain('fast');
    });

    it('handles -er with silent e (nicer -> nice)', () => {
      const result = normalizeWord('nicer');
      expect(result).toContain('nice');
    });

    it('handles doubled consonant comparatives (bigger -> big)', () => {
      const result = normalizeWord('bigger');
      expect(result).toContain('big');
    });

    it('handles -est superlatives (fastest -> fast)', () => {
      const result = normalizeWord('fastest');
      expect(result).toContain('fast');
    });

    it('handles -est with silent e (nicest -> nice)', () => {
      // nicest -> nices (slice -2) which should give nice-ish base
      const result = normalizeWord('nicest');
      expect(result).toContain('nice');
    });
  });

  describe('adverb handling (-ly)', () => {
    it('handles -ly adverbs (quickly -> quick)', () => {
      const result = normalizeWord('quickly');
      expect(result).toContain('quick');
    });

    it('handles -ly adverbs (slowly -> slow)', () => {
      const result = normalizeWord('slowly');
      expect(result).toContain('slow');
    });

    it('does not strip short -ly words', () => {
      // "fly" should stay as "fly"
      const result = normalizeWord('fly');
      expect(result[0]).toBe('fly');
    });
  });
});

describe('lookup', () => {
  const mockDictionary = {
    'cat': [{ s: '猫', p: 'mao', d: ['cat'] }],
    'study': [{ s: '学习', p: 'xue xi', d: ['to study'] }],
    'run': [{ s: '跑', p: 'pao', d: ['to run'] }],
    'big': [{ s: '大', p: 'da', d: ['big'] }],
    'quick': [{ s: '快', p: 'kuai', d: ['quick'] }],
  };

  it('returns null when dictionary is null', () => {
    expect(lookup('cat', null)).toBeNull();
  });

  it('finds exact match', () => {
    const result = lookup('cat', mockDictionary);
    expect(result).toEqual(mockDictionary['cat']);
  });

  it('finds base form from plural', () => {
    const result = lookup('cats', mockDictionary);
    expect(result).toEqual(mockDictionary['cat']);
  });

  it('finds base form from -ies plural', () => {
    const result = lookup('studies', mockDictionary);
    expect(result).toEqual(mockDictionary['study']);
  });

  it('finds base form from gerund with doubled consonant', () => {
    const result = lookup('running', mockDictionary);
    expect(result).toEqual(mockDictionary['run']);
  });

  it('finds base form from comparative with doubled consonant', () => {
    const result = lookup('bigger', mockDictionary);
    expect(result).toEqual(mockDictionary['big']);
  });

  it('finds base form from adverb', () => {
    const result = lookup('quickly', mockDictionary);
    expect(result).toEqual(mockDictionary['quick']);
  });

  it('returns null for words not in dictionary', () => {
    const result = lookup('xyzzy', mockDictionary);
    expect(result).toBeNull();
  });

  it('returns null for empty input', () => {
    const result = lookup('', mockDictionary);
    expect(result).toBeNull();
  });
});
