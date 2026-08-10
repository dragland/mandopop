import { describe, it, expect } from 'vitest';
import { parseHanzi } from '../lib/traverse/card_parser.js';

// Content field maps arrive with lowercased keys (CardDoc semantics).
function doc(fields) {
  return {
    fields: new Map(Object.entries(fields).map(([k, v]) => [k.toLowerCase(), v])),
  };
}

describe('parseHanzi', () => {
  it('reads a cloze card from Characters', () => {
    expect(parseHanzi(doc({
      Characters: '明天',
      Pinyin: '{{c1::míng}}{{c2::tiān}}',
      English: 'tomorrow',
    }))).toBe('明天');
  });

  it('survives the MSLK Chinese/Pinyin swap — Han decides, not the name', () => {
    expect(parseHanzi(doc({
      Chinese: 'Nǐ shuō tā hěn hǎo.',
      Pinyin: '你说她很好。',
      'English Translation': 'You say she is fine.',
    }))).toBe('你说她很好。');

    expect(parseHanzi(doc({
      Chinese: '你说她很好。',
      Pinyin: 'Nǐ shuō tā hěn hǎo.',
      'English Translation': 'You say she is fine.',
    }))).toBe('你说她很好。');
  });

  it('prefers the sentence over the highlighted word on MB Sentence cards', () => {
    expect(parseHanzi(doc({
      Sentence: '他去==祭奠==了。',
      Word: '祭奠',
      'Usage Definition': 'to pay respects',
    }))).toBe('他去祭奠了。');
  });

  it('takes only the first Han run on word cards', () => {
    expect(parseHanzi(doc({
      WORD: '祭奠 1',
      MEANING: 'to hold a memorial',
    }))).toBe('祭奠');

    expect(parseHanzi(doc({
      HANZI: '中',
      PINYIN: '<p>zhōng</p>',
      KEYWORD: '<p>Middle/Centre</p>',
    }))).toBe('中');
  });

  it('reads PROP components', () => {
    expect(parseHanzi(doc({ COMPONENT: '十 ![](props.png)' }))).toBe('十');
  });

  it('yields nothing for sound-only cards — no layout claims their fields', () => {
    expect(parseHanzi(doc({
      ACTOR: 'Jackie Chan 成龙',
      'PINYIN INITIAL': 'zh',
    }))).toBe(null);
    expect(parseHanzi(doc({ 'Word 1': 'ji2', 'Word 2': 'qi2' }))).toBe(null);
  });

  it('yields nothing when neither field of the pair carries Han', () => {
    expect(parseHanzi(doc({ Chinese: 'Nǐ hǎo.', Pinyin: 'ni hao' }))).toBe(null);
  });

  it('yields nothing when stripping removes all Han', () => {
    expect(parseHanzi(doc({ Chinese: '[人（HANZI）](/Mandarin_Blueprint/人)' }))).toBe(null);
  });

  it('skips blank fields when choosing a layout', () => {
    expect(parseHanzi(doc({ Chinese: '   ', Sentence: '他很好。' }))).toBe('他很好。');
  });
});
