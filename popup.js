/**
 * Mandopop Settings Popup
 */

document.addEventListener('DOMContentLoaded', async () => {
  const enabledToggle = document.getElementById('enabled');
  const showAudioToggle = document.getElementById('showAudio');
  const fontSizeSlider = document.getElementById('fontSize');
  const fontSizeValue = document.getElementById('fontSizeValue');
  const previewChinese = document.getElementById('previewChinese');

  // Load current settings
  const settings = await chrome.storage.sync.get(['enabled', 'showAudio', 'fontSize']);

  enabledToggle.checked = settings.enabled !== false;
  showAudioToggle.checked = settings.showAudio !== false;
  fontSizeSlider.value = settings.fontSize || 24;
  fontSizeValue.textContent = `${fontSizeSlider.value}px`;
  previewChinese.style.fontSize = `${fontSizeSlider.value}px`;

  // Save on change
  enabledToggle.addEventListener('change', () => {
    chrome.storage.sync.set({ enabled: enabledToggle.checked });
  });

  showAudioToggle.addEventListener('change', () => {
    chrome.storage.sync.set({ showAudio: showAudioToggle.checked });
  });

  fontSizeSlider.addEventListener('input', () => {
    const size = fontSizeSlider.value;
    fontSizeValue.textContent = `${size}px`;
    previewChinese.style.fontSize = `${size}px`;
    chrome.storage.sync.set({ fontSize: parseInt(size, 10) });
  });
});
