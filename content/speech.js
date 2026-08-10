/**
 * Mandopop shared speech helper (classic no-build script, loaded first).
 * One voice policy for every surface that speaks — the lookup popup and
 * the weave tooltip must not fork pronunciation behavior.
 */
(function () {
  'use strict';

  // Find best Chinese voice (prefer Taiwan)
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

  // Fire-and-forget pronunciation with the shared voice policy.
  function speak(text, rate = 0.85) {
    if (!text || !('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();

    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-TW';
    utterance.rate = rate;

    const voices = window.speechSynthesis.getVoices();
    if (voices.length > 0) {
      const voice = findChineseVoice(voices);
      if (voice) utterance.voice = voice;
    }

    window.speechSynthesis.speak(utterance);
  }

  globalThis.MandopopSpeech = { findChineseVoice, speak };
})();
