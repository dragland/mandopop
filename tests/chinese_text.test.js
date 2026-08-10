import { describe, it, expect } from 'vitest';
import { hasHan, hanRuns, stripMarkup, trimPunctuation } from '../lib/traverse/chinese_text.js';

describe('hasHan / hanRuns', () => {
  it('detects Han characters', () => {
    expect(hasHan('你好')).toBe(true);
    expect(hasHan('Nǐ hǎo')).toBe(false);
    expect(hasHan('〇')).toBe(true); // ideographic zero
  });

  it('splits runs on non-Han characters', () => {
    expect(hanRuns('他1776年。')).toEqual(['他', '年']);
    expect(hanRuns('十 ![](props.png)')).toEqual(['十']);
    expect(hanRuns('hello')).toEqual([]);
  });
});

describe('stripMarkup', () => {
  it('removes HTML tags', () => {
    expect(stripMarkup('<p>zhōng</p>')).toBe('zhōng');
  });

  it('removes markdown links entirely, text included', () => {
    // A wrapped course reference carries literal Han characters — keeping
    // its text would add a second Han-bearing string to the card.
    expect(stripMarkup('你好 [人（HANZI）](/Mandarin_Blueprint/人)')).toBe('你好');
    expect(stripMarkup('十 ![](image.png)')).toBe('十');
  });

  it('unescapes backslash escapes and strips highlight markers', () => {
    expect(stripMarkup('\\*text\\*')).toBe('*text*');
    expect(stripMarkup('他去==祭奠==了')).toBe('他去祭奠了');
  });

  it('decodes entities sequentially, tags first', () => {
    expect(stripMarkup('&amp;lt;')).toBe('<');
    expect(stripMarkup('A&nbsp;B')).toBe('A B');
  });

  it('collapses unicode whitespace', () => {
    expect(stripMarkup('你　 好')).toBe('你 好');
  });
});

describe('trimPunctuation', () => {
  it('trims punctuation from both ends, keeping letters and digits', () => {
    expect(trimPunctuation('谢谢！')).toBe('谢谢');
    expect(trimPunctuation('。你好。')).toBe('你好');
    expect(trimPunctuation('1776年')).toBe('1776年');
  });

  it('drops trailing prompt disambiguators', () => {
    expect(trimPunctuation('在（1）')).toBe('在');
    expect(trimPunctuation('在(2)')).toBe('在');
  });
});
