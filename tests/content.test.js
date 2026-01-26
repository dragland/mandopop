import { describe, it, expect } from 'vitest';

/**
 * Tests for content script logic
 * These test the pure functions/logic that can be extracted from content.js
 */

// Selection validation logic (extracted from content.js doSelection)
function isValidSelection(text) {
  const MAX_SELECTION_LENGTH = 100;

  if (!text || text.length === 0 || text.length > MAX_SELECTION_LENGTH) {
    return false;
  }

  // Reject whitespace-only or numbers-only
  if (/^[\s\d]+$/.test(text)) {
    return false;
  }

  // Reject if contains Chinese characters (already Chinese, no need to translate)
  if (/[\u4e00-\u9fff]/.test(text)) {
    return false;
  }

  return true;
}

// Voice selection logic (extracted from content.js findChineseVoice)
function findChineseVoice(voices) {
  const chineseVoices = voices.filter(v => v.lang.startsWith('zh'));

  const preferredVoices = [
    { name: 'meijia', lang: 'zh-TW' },
    { name: 'shelley', lang: 'zh-TW' },
    { name: 'sandy', lang: 'zh-TW' },
    { name: 'flo', lang: 'zh-TW' },
  ];

  for (const preferred of preferredVoices) {
    const match = chineseVoices.find(v =>
      v.name.toLowerCase().includes(preferred.name) && v.lang === preferred.lang
    );
    if (match) return match;
  }

  const twVoice = chineseVoices.find(v => v.lang === 'zh-TW');
  if (twVoice) return twVoice;

  const cnVoice = chineseVoices.find(v => v.lang === 'zh-CN');
  if (cnVoice) return cnVoice;

  return chineseVoices[0] || null;
}

// Popup positioning logic (simplified, extracted from positionPopup)
function calculatePopupPosition(x, y, popupWidth, popupHeight, viewportWidth, viewportHeight) {
  const padding = 10;

  let left = x;
  let top = y + 10; // 10px below selection

  // Prevent overflow on right
  if (left + popupWidth > viewportWidth - padding) {
    left = viewportWidth - popupWidth - padding;
  }
  // Prevent overflow on left
  if (left < padding) {
    left = padding;
  }

  // Prevent overflow on bottom - flip above selection
  if (top + popupHeight > viewportHeight - padding) {
    top = y - popupHeight - 10;
  }
  // Prevent overflow on top
  if (top < padding) {
    top = padding;
  }

  return { left, top };
}

describe('isValidSelection', () => {
  describe('accepts valid selections', () => {
    it('accepts regular English words', () => {
      expect(isValidSelection('hello')).toBe(true);
    });

    it('accepts words with spaces', () => {
      expect(isValidSelection('hello world')).toBe(true);
    });

    it('accepts words with punctuation', () => {
      expect(isValidSelection('hello!')).toBe(true);
    });

    it('accepts mixed alphanumeric', () => {
      expect(isValidSelection('test123')).toBe(true);
    });
  });

  describe('rejects invalid selections', () => {
    it('rejects empty string', () => {
      expect(isValidSelection('')).toBe(false);
    });

    it('rejects whitespace only', () => {
      expect(isValidSelection('   ')).toBe(false);
    });

    it('rejects numbers only', () => {
      expect(isValidSelection('12345')).toBe(false);
    });

    it('rejects numbers with spaces', () => {
      expect(isValidSelection('123 456')).toBe(false);
    });

    it('rejects selections longer than 100 characters', () => {
      const longText = 'a'.repeat(101);
      expect(isValidSelection(longText)).toBe(false);
    });
  });

  describe('rejects Chinese text', () => {
    it('rejects Chinese characters', () => {
      expect(isValidSelection('你好')).toBe(false);
    });

    it('rejects mixed Chinese and English', () => {
      expect(isValidSelection('hello 你好')).toBe(false);
    });

    it('rejects single Chinese character', () => {
      expect(isValidSelection('猫')).toBe(false);
    });
  });

  describe('edge cases', () => {
    it('accepts exactly 100 characters', () => {
      const text = 'a'.repeat(100);
      expect(isValidSelection(text)).toBe(true);
    });

    it('rejects 101 characters', () => {
      const text = 'a'.repeat(101);
      expect(isValidSelection(text)).toBe(false);
    });
  });
});

describe('findChineseVoice', () => {
  it('returns null when no voices available', () => {
    expect(findChineseVoice([])).toBeNull();
  });

  it('returns null when no Chinese voices available', () => {
    const voices = [
      { name: 'Samantha', lang: 'en-US' },
      { name: 'Daniel', lang: 'en-GB' },
    ];
    expect(findChineseVoice(voices)).toBeNull();
  });

  it('prefers Meijia (Taiwan) voice', () => {
    const voices = [
      { name: 'Ting-Ting', lang: 'zh-CN' },
      { name: 'Meijia', lang: 'zh-TW' },
      { name: 'Sinji', lang: 'zh-HK' },
    ];
    const result = findChineseVoice(voices);
    expect(result.name).toBe('Meijia');
  });

  it('falls back to other Taiwan voices', () => {
    const voices = [
      { name: 'Ting-Ting', lang: 'zh-CN' },
      { name: 'Sandy', lang: 'zh-TW' },
    ];
    const result = findChineseVoice(voices);
    expect(result.name).toBe('Sandy');
  });

  it('falls back to any zh-TW voice', () => {
    const voices = [
      { name: 'Ting-Ting', lang: 'zh-CN' },
      { name: 'SomeOther', lang: 'zh-TW' },
    ];
    const result = findChineseVoice(voices);
    expect(result.lang).toBe('zh-TW');
  });

  it('falls back to zh-CN when no zh-TW available', () => {
    const voices = [
      { name: 'Ting-Ting', lang: 'zh-CN' },
      { name: 'Sinji', lang: 'zh-HK' },
    ];
    const result = findChineseVoice(voices);
    expect(result.lang).toBe('zh-CN');
  });

  it('falls back to any Chinese voice as last resort', () => {
    const voices = [
      { name: 'Samantha', lang: 'en-US' },
      { name: 'Sinji', lang: 'zh-HK' },
    ];
    const result = findChineseVoice(voices);
    expect(result.lang).toBe('zh-HK');
  });

  it('is case-insensitive for voice names', () => {
    const voices = [
      { name: 'MEIJIA', lang: 'zh-TW' },
    ];
    const result = findChineseVoice(voices);
    expect(result.name).toBe('MEIJIA');
  });
});

describe('calculatePopupPosition', () => {
  const viewportWidth = 1024;
  const viewportHeight = 768;

  describe('normal positioning', () => {
    it('positions popup below and at selection x', () => {
      const result = calculatePopupPosition(100, 200, 200, 100, viewportWidth, viewportHeight);
      expect(result.left).toBe(100);
      expect(result.top).toBe(210); // y + 10
    });
  });

  describe('right edge handling', () => {
    it('prevents popup from overflowing right edge', () => {
      // Selection near right edge
      const result = calculatePopupPosition(900, 200, 200, 100, viewportWidth, viewportHeight);
      expect(result.left).toBe(viewportWidth - 200 - 10); // viewport - popup - padding
    });
  });

  describe('left edge handling', () => {
    it('prevents popup from going off left edge', () => {
      const result = calculatePopupPosition(5, 200, 200, 100, viewportWidth, viewportHeight);
      expect(result.left).toBe(10); // padding
    });
  });

  describe('bottom edge handling', () => {
    it('flips popup above selection when near bottom', () => {
      // Selection near bottom
      const result = calculatePopupPosition(100, 700, 200, 100, viewportWidth, viewportHeight);
      expect(result.top).toBe(700 - 100 - 10); // y - popupHeight - 10
    });
  });

  describe('top edge handling', () => {
    it('prevents popup from going off top edge', () => {
      // Selection at y=5, popup height 100
      // Normal position: 5 + 10 = 15 (below selection)
      // This fits within viewport, so no flip needed
      const result = calculatePopupPosition(100, 5, 200, 100, viewportWidth, viewportHeight);
      expect(result.top).toBe(15);
    });
  });

  describe('corner cases', () => {
    it('handles bottom-right corner', () => {
      const result = calculatePopupPosition(950, 700, 200, 100, viewportWidth, viewportHeight);
      // Should be pushed left and flipped up
      expect(result.left).toBe(viewportWidth - 200 - 10);
      expect(result.top).toBe(700 - 100 - 10);
    });
  });
});
