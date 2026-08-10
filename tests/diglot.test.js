import { describe, it, expect } from 'vitest';
import { buildReplacementMap, matchText, longestKeyLength } from '../lib/diglot.js';

// Minimal dictionary in cedict.json v2 shape. Index arrays are in
// rankForKey order, as the build emits them.
const dictionary = {
  entries: [
    { s: '猫', p: 'māo', d: ['cat (CL:隻|只[zhi1])'] },
    { s: '门', p: 'mén', d: ['gate', 'door', 'CL:扇[shan4]', 'gateway', '(fig.) school'] },
    { s: '学校', p: 'xué xiào', d: ['school', 'CL:所[suo3]'] },
    { s: '快', p: 'kuài', d: ['rapid', 'quick', 'fast'] },
    { s: '起床', p: 'qǐ chuáng', d: ['to get up (out of bed)'] },
    { s: '很', p: 'hěn', d: ['very', 'quite'] },
  ],
  index: {
    'cat': [0],
    'school': [2, 1],
    'fast': [3],
    'get up': [4],
    'very': [5],
  },
};

describe('buildReplacementMap', () => {
  it('maps keys whose known candidate means the key', () => {
    const map = buildReplacementMap(['猫', '学校'], dictionary);
    expect(map['cat']).toEqual({ s: '猫', p: 'māo' });
    expect(map['school']).toEqual({ s: '学校', p: 'xué xiào' });
  });

  it('ignores unknown words entirely', () => {
    expect(buildReplacementMap([], dictionary)).toEqual({});
  });

  it('refuses a known word that merely mentions the key in a late gloss', () => {
    // 门 lists "(fig.) school" — annotation stripping makes that sense
    // "school", but 学校 has it at an earlier position; with only 门
    // known, "school" must still map because the sense IS an exact gloss…
    const map = buildReplacementMap(['门'], dictionary);
    expect(map['school']).toEqual({ s: '门', p: 'mén' });
    // …but a better-positioned known candidate always wins.
    const both = buildReplacementMap(['门', '学校'], dictionary);
    expect(both['school']).toEqual({ s: '学校', p: 'xué xiào' });
  });

  it('accepts non-primary but exact senses', () => {
    expect(buildReplacementMap(['快'], dictionary)['fast']).toEqual({ s: '快', p: 'kuài' });
  });

  it('rejects senses qualified by a leading parenthetical', () => {
    // 去 lists "(of a time etc) last" — it means "last" only inside bound
    // compounds like 去年, and swapping it standalone wove "last week"
    // into the non-word 去周 on a real page.
    const qualified = {
      entries: [
        { s: '去', p: 'qù', d: ['to go', '(of a time etc) last', 'to remove'] },
        // 吗's colloquial reading outranks 什么 for "what" on raw frequency
        // — the frequency belongs to the question particle, not this sense.
        { s: '吗', p: 'má', d: ['(coll.) what?'] },
        { s: '什么', p: 'shén me', d: ['what?', 'something; anything'] },
      ],
      index: { 'last': [0], 'go': [0], 'what': [1, 2] },
    };
    const map = buildReplacementMap(['去', '吗', '什么'], qualified);
    expect(map['last']).toBeUndefined();
    expect(map['go']).toEqual({ s: '去', p: 'qù' });
    expect(map['what']).toEqual({ s: '什么', p: 'shén me' });
  });
});

describe('matchText', () => {
  const map = buildReplacementMap(['猫', '学校', '起床', '很'], dictionary);

  it('replaces single words, preserving surrounding text', () => {
    expect(matchText('my cat sleeps', map)).toEqual([
      { text: 'my ' },
      { original: 'cat', s: '猫', p: 'māo' },
      { text: ' sleeps' },
    ]);
  });

  it('resolves inflections through normalizeWord variants', () => {
    expect(matchText('two cats', map)).toEqual([
      { text: 'two ' },
      { original: 'cats', s: '猫', p: 'māo' },
    ]);
  });

  it('prefers the longest phrase over its first word', () => {
    const segments = matchText('I get up early', map);
    expect(segments).toContainEqual({ original: 'get up', s: '起床', p: 'qǐ chuáng' });
  });

  it('never matches a phrase across punctuation', () => {
    expect(matchText('you get, up now', map)).toBe(null);
  });

  it('skips all-caps tokens — acronyms are not words, and CAT scans stay CAT scans', () => {
    expect(matchText('the CAT scan', map)).toBe(null);
    expect(matchText('a very loud CAT', map)).toEqual([
      { text: 'a ' },
      { original: 'very', s: '很', p: 'hěn' },
      { text: ' loud CAT' },
    ]);
  });

  it('returns null when nothing matches', () => {
    expect(matchText('nothing here', map)).toBe(null);
    expect(matchText('', map)).toBe(null);
  });

  it('never matches inherited Object.prototype keys', () => {
    // "constructor" is an ordinary English word AND a prototype property of
    // every plain object; indexing instead of hasOwn turned it into the
    // literal text "undefined" on any programming page.
    expect(matchText('the constructor pattern', map)).toBe(null);
    expect(matchText('hasOwnProperty toString valueOf', map)).toBe(null);
  });

  it('measures the longest key so single-word maps skip phrase spans', () => {
    expect(longestKeyLength({ cat: {}, very: {} })).toBe(1);
    expect(longestKeyLength({ cat: {}, 'get up': {} })).toBe(2);
    // A 1-token limit must not stop "get up" from matching when present.
    expect(matchText('cats sleep', { cat: { s: '猫', p: 'māo' } }, 1)).toEqual([
      { original: 'cats', s: '猫', p: 'māo' },
      { text: ' sleep' },
    ]);
  });

  it('replaces capitalized sentence-initial words', () => {
    expect(matchText('Cats are fine.', map)).toEqual([
      { original: 'Cats', s: '猫', p: 'māo' },
      { text: ' are fine.' },
    ]);
  });
});
