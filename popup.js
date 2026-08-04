/**
 * Mandopop Settings Popup
 */

document.addEventListener('DOMContentLoaded', async () => {
  const showAudioToggle = document.getElementById('showAudio');
  const fontSizeSlider = document.getElementById('fontSize');
  const fontSizeValue = document.getElementById('fontSizeValue');
  const previewChinese = document.getElementById('previewChinese');

  // Load current settings
  const settings = await chrome.storage.sync.get(['showAudio', 'fontSize']);

  showAudioToggle.checked = settings.showAudio !== false;
  fontSizeSlider.value = settings.fontSize || 24;
  fontSizeValue.textContent = `${fontSizeSlider.value}px`;
  previewChinese.style.fontSize = `${fontSizeSlider.value}px`;

  // Persist a setting, surfacing failures instead of silently dropping them.
  function save(items) {
    chrome.storage.sync.set(items, () => {
      if (chrome.runtime.lastError) {
        console.error('[Mandopop] Failed to save settings:', chrome.runtime.lastError);
      }
    });
  }

  // Save on change
  showAudioToggle.addEventListener('change', () => {
    save({ showAudio: showAudioToggle.checked });
  });

  // Update the live preview on every input, but only persist on 'change'
  // (fires once on release) to avoid hammering chrome.storage.sync's write
  // quota (~120 writes/min) while dragging.
  fontSizeSlider.addEventListener('input', () => {
    const size = fontSizeSlider.value;
    fontSizeValue.textContent = `${size}px`;
    previewChinese.style.fontSize = `${size}px`;
  });

  fontSizeSlider.addEventListener('change', () => {
    save({ fontSize: parseInt(fontSizeSlider.value, 10) });
  });
});
