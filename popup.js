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

  // --- Traverse ---

  const signInForm = document.getElementById('signInForm');
  const signInBtn = document.getElementById('signInBtn');
  const accountPanel = document.getElementById('traverseAccount');
  const statusLine = document.getElementById('traverseStatus');
  const errorLine = document.getElementById('traverseError');
  const chinglishToggle = document.getElementById('chinglish');
  const syncBtn = document.getElementById('syncBtn');
  const signOutBtn = document.getElementById('signOutBtn');

  let syncing = false;

  // Coverage names a state — the healthy one is a positive assertion, and
  // the broken one leads with a number that is zero when healthy.
  function coverageText(state) {
    const { words, cards, fetched, readable } = state;
    if (cards === undefined) return 'Not synced yet';
    if (fetched < cards) return `${words} words · indexing ${fetched} of ${cards} cards`;
    if (readable < cards) return `${words} words · ${cards - readable} of ${cards} cards unreadable`;
    return `${words} words · all ${cards} cards indexed`;
  }

  async function renderTraverse() {
    const { traverseAuth, traverseSyncState } = await chrome.storage.local.get([
      'traverseAuth', 'traverseSyncState',
    ]);
    const signedIn = Boolean(traverseAuth);
    signInForm.hidden = signedIn;
    accountPanel.hidden = !signedIn;
    if (!signedIn) {
      errorLine.textContent = '';
      return;
    }

    const state = traverseSyncState ?? {};
    statusLine.textContent = syncing
      ? `${traverseAuth.email} · syncing…`
      : `${traverseAuth.email} · ${coverageText(state)}`;
    errorLine.textContent = state.lastError ?? '';
  }

  signInForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    signInBtn.disabled = true;
    errorLine.textContent = '';
    const response = await chrome.runtime.sendMessage({
      type: 'traverse.signIn',
      email: document.getElementById('traverseEmail').value.trim(),
      password: document.getElementById('traversePassword').value,
    });
    signInBtn.disabled = false;
    if (response?.ok) {
      runSync(); // joins the drain the background already started
    } else {
      errorLine.textContent = response?.error ?? 'Sign-in failed';
    }
  });

  async function runSync() {
    syncing = true;
    syncBtn.disabled = true;
    renderTraverse();
    await chrome.runtime.sendMessage({ type: 'traverse.sync' });
    syncing = false;
    syncBtn.disabled = false;
    renderTraverse();
  }

  syncBtn.addEventListener('click', runSync);

  signOutBtn.addEventListener('click', async () => {
    await chrome.runtime.sendMessage({ type: 'traverse.signOut' });
    renderTraverse();
  });

  chinglishToggle.addEventListener('change', () => {
    save({ chinglish: chinglishToggle.checked });
  });

  const { chinglish } = await chrome.storage.sync.get(['chinglish']);
  chinglishToggle.checked = chinglish !== false;

  // Live-update while the background sync writes progress.
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && ('traverseSyncState' in changes || 'traverseAuth' in changes)) {
      renderTraverse();
    }
  });

  renderTraverse();
});
