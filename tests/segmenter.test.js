import { describe, it, expect } from 'vitest';
import { candidates, segment } from '../lib/traverse/segmenter.js';
import { readSegmentationCases } from './fixtures.js';

const segmentationCases = readSegmentationCases();

describe('segment', () => {
  describe('shared parity fixtures', () => {
    it.each(segmentationCases)('$name', ({ text, vocab, expected }) => {
      expect(segment(text, (w) => vocab.has(w))).toEqual(expected);
    });
  });
});

describe('candidates', () => {
  it('yields every 2..4 character substring of each run', () => {
    expect(candidates('东西南')).toEqual(new Set(['东西', '西南', '东西南']));
  });

  it('never probes single characters or non-Han text', () => {
    expect(candidates('hello 123')).toEqual(new Set());
    expect(candidates('中')).toEqual(new Set());
  });

  it('does not cross run boundaries', () => {
    expect(candidates('东西, 南北')).toEqual(new Set(['东西', '南北']));
  });
});
