import { describe, it, expect } from 'vitest';
import { isSoundOnly, summarizeCards, brokenTemplate } from '../lib/traverse/sync.js';

describe('isSoundOnly', () => {
  it('matches by suffix, prefixed or bare', () => {
    expect(isSoundOnly('/Mandarin_Blueprint/ACTOR REVIEW')).toBe(true);
    expect(isSoundOnly('SET REVIEW')).toBe(true);
    expect(isSoundOnly('/Mandarin_Blueprint/Minimal Pairs')).toBe(true);
    expect(isSoundOnly('/Mandarin_Blueprint/PROP REVIEW')).toBe(false);
    expect(isSoundOnly('/Mandarin_Blueprint/MSLK Card')).toBe(false);
  });
});

describe('summarizeCards', () => {
  it('marks a card sound-only when ANY of its rows is', () => {
    const cards = summarizeCards([
      { cardId: 'a', template: '/x/ACTOR REVIEW', author: 'MB', suspended: false },
      { cardId: 'a', template: '/x/MOVIE REVIEW', author: 'MB', suspended: false },
    ]);
    expect(cards.get('a').soundOnly).toBe(true);
  });

  it('marks a card started when ANY of its rows is unsuspended', () => {
    const cards = summarizeCards([
      { cardId: 'a', template: '/x/MSLK Card', author: 'MB', suspended: true },
      { cardId: 'a', template: '/x/MSLK Card', author: 'MB', suspended: false },
      { cardId: 'b', template: '/x/MSLK Card', author: 'MB', suspended: true },
    ]);
    expect(cards.get('a').started).toBe(true);
    expect(cards.get('b').started).toBe(false);
  });

  it('drops rows without a card id', () => {
    expect(summarizeCards([{ cardId: '', template: 't', author: 'MB', suspended: false }]).size).toBe(0);
  });
});

describe('brokenTemplate', () => {
  const outcomes = (template, total, read) =>
    Array.from({ length: total }, (_, i) => ({ template, read: i < read }));

  it('flags a template that reads nothing on a small sample', () => {
    expect(brokenTemplate(outcomes('MSLK', 3, 0))?.template).toBe('MSLK');
    expect(brokenTemplate(outcomes('MSLK', 2, 0))).toBe(null);
  });

  it('flags a template under half readable on a large sample', () => {
    expect(brokenTemplate(outcomes('MSLK', 20, 9))?.template).toBe('MSLK');
    expect(brokenTemplate(outcomes('MSLK', 20, 10))).toBe(null);
  });

  it('accepts healthy templates', () => {
    expect(brokenTemplate([...outcomes('MSLK', 30, 30), ...outcomes('PROP', 2, 0)])).toBe(null);
  });
});
