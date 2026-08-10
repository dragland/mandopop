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

  it('weaves only through the canonical translation, never a side-sense', () => {
    // 门 lists "(fig.) school", but the canonical candidate for "school"
    // is 学校 — knowing only 门, the key stays English.
    expect(buildReplacementMap(['门'], dictionary)['school']).toBeUndefined();
    expect(buildReplacementMap(['门', '学校'], dictionary)['school'])
      .toEqual({ s: '学校', p: 'xué xiào' });
  });

  it('accepts non-primary but exact senses', () => {
    expect(buildReplacementMap(['快'], dictionary)['fast']).toEqual({ s: '快', p: 'kuài' });
  });

  it('splits canonicals by gloss capitalization — months and proper nouns', () => {
    const cased = {
      entries: [
        { s: '游行', p: 'yóu xíng', d: ['to march; to parade', 'procession; march'] },
        { s: '三月', p: 'Sān yuè', d: ['March', 'third month (of the lunar year)'] },
        { s: '瓷器', p: 'cí qì', d: ['porcelain', 'china'] },
        { s: '中国', p: 'Zhōng guó', d: ['China'] },
      ],
      index: { 'march': [0, 1], 'china': [2, 3] },
    };
    const map = buildReplacementMap(['游行', '三月', '瓷器', '中国'], cased);
    expect(matchText('19 March 2023', map)).toContainEqual({ original: 'March', s: '三月', p: 'Sān yuè' });
    expect(matchText('they march on', map)).toContainEqual({ original: 'march', s: '游行', p: 'yóu xíng' });
    expect(matchText('made in China', map)).toContainEqual({ original: 'China', s: '中国', p: 'Zhōng guó' });
    expect(matchText('fine china plates', map)).toContainEqual({ original: 'china', s: '瓷器', p: 'cí qì' });
  });

  it('a proper-only key never weaves a lowercase token', () => {
    const cased = {
      entries: [{ s: '六月', p: 'Liù yuè', d: ['June'] }],
      index: { 'june': [0] },
    };
    const map = buildReplacementMap(['六月'], cased);
    expect(matchText('came home in June', map)).toContainEqual({ original: 'June', s: '六月', p: 'Liù yuè' });
    expect(matchText('the june bug', map)).toBe(null);
  });

  it('a bare sense inherits its run’s register qualifier — 乎 never means "than"', () => {
    const classical = {
      entries: [
        { s: '乎', p: 'hū', d: ['(classical particle similar to 於|于[yu2]) in', 'at', 'from', 'because', 'than'] },
      ],
      index: { 'than': [0], 'because': [0] },
    };
    const map = buildReplacementMap(['乎'], classical);
    expect(map['than']).toBeUndefined();
    expect(map['because']).toBeUndefined();
  });

  it('a strictly better sense position beats frequency for the canonical', () => {
    // The build ranks positions 0 and 1 as one tier, so ubiquitous 文
    // (culture at #1) outranks 文化 (culture at #0) in the index; the
    // swap must still pick 文化.
    const tiered = {
      entries: [
        { s: '文', p: 'wén', d: ['language', 'culture', 'writing'] },
        { s: '文化', p: 'wén huà', d: ['culture', 'civilization'] },
      ],
      index: { 'culture': [0, 1] },
    };
    expect(buildReplacementMap(['文', '文化'], tiered)['culture'])
      .toEqual({ s: '文化', p: 'wén huà' });
  });

  it('refuses trailing abbreviation and honorific marks', () => {
    const marked = {
      entries: [
        { s: '中', p: 'Zhōng', d: ['China (abbr.)', 'middle'] },
        { s: '中国', p: 'Zhōng guó', d: ['China'] },
        { s: '高', p: 'gāo', d: ['high', 'tall', 'your (honorific)'] },
      ],
      index: { 'china': [0, 1], 'your': [2] },
    };
    const map = buildReplacementMap(['中', '中国', '高'], marked);
    expect(map['china']).toEqual({ proper: { s: '中国', p: 'Zhōng guó' } });
    expect(map['your']).toBeUndefined();
  });

  it('bound forms and surnames never weave, and the label scopes its run', () => {
    const bound = {
      entries: [
        { s: '隐', p: 'yǐn', d: ['(bound form) secret; hidden; concealed; crypto-'] },
        { s: '雷', p: 'léi', d: ['thunder', '(bound form) (military) mine'] },
        { s: '从', p: 'Cóng', d: ['surname Cong'] },
      ],
      index: { 'concealed': [0], 'thunder': [1], 'surname cong': [2] },
    };
    const map = buildReplacementMap(['隐', '雷', '从'], bound);
    expect(map['concealed']).toBeUndefined();
    expect(map['thunder']).toEqual({ s: '雷', p: 'léi' }); // precedes the bound-form run
    expect(map['surname cong']).toBeUndefined();
  });

  it('domain labels do not disqualify — "(meteorology) climate" IS climate', () => {
    const domains = {
      entries: [
        { s: '气候', p: 'qì hòu', d: ['(meteorology) climate', '(fig.) climate; prevailing conditions'] },
        { s: '候', p: 'hòu', d: ['to wait', 'season', 'climate'] },
        { s: '那', p: 'nà', d: ['(specifier) that; the; those', '(pronoun) that'] },
        { s: '彼', p: 'bǐ', d: ['that', 'those'] },
      ],
      index: { 'climate': [0, 1], 'that': [2, 3] },
    };
    const map = buildReplacementMap(['气候', '候', '那', '彼'], domains);
    expect(map['climate']).toEqual({ s: '气候', p: 'qì hòu' });
    expect(map['that']).toEqual({ s: '那', p: 'nà' });
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

  it('refuses false stems — has is not "ha", being is not a bee', () => {
    const stems = {
      entries: [
        { s: '哈', p: 'hā', d: ['ha!'] },
        { s: '蜜蜂', p: 'mì fēng', d: ['bee'] },
        { s: '我们', p: 'wǒ men', d: ['we', 'us'] },
        { s: '有', p: 'yǒu', d: ['to have'] },
      ],
      index: { 'ha': [0], 'bee': [1], 'us': [2], 'we': [2], 'have': [3] },
    };
    const map = buildReplacementMap(['哈', '蜜蜂', '我们', '有'], stems);
    expect(matchText('she has been busy', map)).toContainEqual({ original: 'has', s: '有', p: 'yǒu' });
    expect(matchText('a being of light', map)).toBe(null);
    expect(matchText('we used it', map)).toContainEqual({ original: 'we', s: '我们', p: 'wǒ men' });
    expect(matchText('we used it', map)).not.toContainEqual(
      expect.objectContaining({ original: 'used', s: '我们' }),
    );
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
