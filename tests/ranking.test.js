import { describe, it, expect } from 'vitest';
import { exactGlossRank, rankForKey, readFrequencies } from '../scripts/preprocess_cedict.js';

const entry = (s, ...definitions) => ({ s, p: '', d: definitions });

describe('exactGlossRank', () => {
  it('reports position 0 when the leading sense is the key', () => {
    expect(exactGlossRank(entry('八', 'eight'), 'eight')).toBe(0);
  });

  it('ignores a leading article or infinitive marker', () => {
    expect(exactGlossRank(entry('买', 'to buy; to purchase'), 'buy')).toBe(0);
    expect(exactGlossRank(entry('猫', 'a cat'), 'cat')).toBe(0);
  });

  it('counts senses across separate definitions', () => {
    expect(exactGlossRank(entry('走', 'to walk; to go', 'to run'), 'run')).toBe(2);
  });

  it('ignores CC-CEDICT sense annotations', () => {
    // Classifiers and register markers qualify a gloss; they are not part of it.
    expect(exactGlossRank(entry('猫', 'cat (CL:隻|只[zhi1])'), 'cat')).toBe(0);
    expect(exactGlossRank(entry('猫', '(dialect) to hide oneself', 'cat'), 'cat')).toBe(1);
  });

  it('returns Infinity when the key only appears inside a longer sense', () => {
    // "two sides" contains "two" but does not mean it.
    expect(exactGlossRank(entry('二侧', 'two sides'), 'two')).toBe(Infinity);
  });
});

describe('rankForKey', () => {
  // 十 is the answer for "ten"; 旬时 is an obscure word merely glossed with it.
  const tenEntries = [entry('旬时', 'ten days'), entry('十', 'ten; 10')];
  const frequencies = readFrequencies('十\t107.61\n旬时\t0\n');

  it('puts the word that means the key ahead of one that mentions it', () => {
    expect(rankForKey('ten', [0, 1], tenEntries, frequencies)).toEqual([1, 0]);
  });

  it('uses frequency to separate entries that both mean the key', () => {
    const entries = [entry('困倦', 'tired'), entry('累', 'tired')];
    const freq = readFrequencies('累\t70.8\n困倦\t0.15\n');
    expect(rankForKey('tired', [0, 1], entries, freq)).toEqual([1, 0]);
  });

  it('does not let a frequent word win on an archaic secondary sense', () => {
    // 门 really does mean "school (of thought)", but it is not the answer to "school".
    const entries = [
      entry('门', 'door; gate; opening; valve; family; sect; school of thought; school'),
      entry('学校', 'school; CL:所[suo3]'),
    ];
    const freq = readFrequencies('门\t500\n学校\t50\n');
    expect(rankForKey('school', [0, 1], entries, freq)).toEqual([1, 0]);
  });

  it('treats an unknown word as least frequent rather than dropping it', () => {
    const entries = [entry('甲', 'test'), entry('乙', 'test')];
    const freq = readFrequencies('乙\t5\n');
    expect(rankForKey('test', [0, 1], entries, freq)).toEqual([1, 0]);
  });

  it('is stable for candidates that tie on every signal', () => {
    const entries = [entry('甲', 'x'), entry('乙', 'x'), entry('丙', 'x')];
    const freq = new Map();
    expect(rankForKey('x', [2, 0, 1], entries, freq)).toEqual([0, 1, 2]);
  });

  it('caps a key at ten entries', () => {
    const entries = Array.from({ length: 25 }, (_, i) => entry(`w${i}`, 'many'));
    const ids = entries.map((_, i) => i);
    expect(rankForKey('many', ids, entries, new Map())).toHaveLength(10);
  });
});

describe('readFrequencies', () => {
  it('parses tab separated values and skips malformed lines', () => {
    const freq = readFrequencies('的\t50155.13\nbroken line\n\n我\t50147.83\n');
    expect(freq.get('的')).toBeCloseTo(50155.13);
    expect(freq.get('我')).toBeCloseTo(50147.83);
    expect(freq.size).toBe(2);
  });
});
