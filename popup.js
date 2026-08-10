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
  const diglotToggle = document.getElementById('diglotWeave');
  const syncBtn = document.getElementById('syncBtn');
  const signOutBtn = document.getElementById('signOutBtn');

  // Coverage names a state — the healthy one is a positive assertion, and
  // the broken one leads with a number that is zero when healthy. Progress
  // is read from the persisted sync state, not a popup-local flag, so a
  // popup opened mid-drain still shows the drain.
  function coverageText(state) {
    const { words, cards, fetched, readable, syncing } = state;
    if (cards === undefined) return syncing ? 'syncing…' : 'Not synced yet';
    if (syncing) return `${words ?? 0} words · indexing ${fetched ?? 0} of ${cards} cards`;
    if (fetched < cards) return `${words} words · ${fetched} of ${cards} cards indexed`;
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

    const state = traverseSyncState ?? {};
    if (signedIn) {
      statusLine.textContent = `${traverseAuth.email} · ${coverageText(state)}`;
    }
    // Signed out, lastError still renders — "session expired" must not
    // vanish along with the account panel.
    errorLine.textContent = state.lastError ?? '';
  }

  signInForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    signInBtn.disabled = true;
    signInBtn.textContent = 'Signing in…';
    errorLine.textContent = '';
    try {
      const response = await chrome.runtime.sendMessage({
        type: 'traverse.signIn',
        email: document.getElementById('traverseEmail').value.trim(),
        password: document.getElementById('traversePassword').value,
      });
      if (response?.ok) {
        runSync(); // joins the drain the background already started
      } else {
        errorLine.textContent = response?.error ?? 'Sign-in failed';
      }
    } catch (error) {
      errorLine.textContent = `Sign-in interrupted: ${error.message}`;
    } finally {
      signInBtn.disabled = false;
      signInBtn.textContent = 'Sign in';
    }
  });

  async function runSync() {
    syncBtn.disabled = true;
    try {
      const response = await chrome.runtime.sendMessage({ type: 'traverse.sync' });
      if (response?.status === 'failure' && response.error) {
        errorLine.textContent = response.error;
      }
    } catch (error) {
      errorLine.textContent = `Sync interrupted: ${error.message}`;
    } finally {
      syncBtn.disabled = false;
      renderTraverse();
    }
  }

  syncBtn.addEventListener('click', runSync);

  signOutBtn.addEventListener('click', async () => {
    try {
      await chrome.runtime.sendMessage({ type: 'traverse.signOut' });
    } catch (error) {
      errorLine.textContent = `Sign-out failed: ${error.message}`;
    }
    renderTraverse();
  });

  diglotToggle.addEventListener('change', () => {
    save({ diglotWeave: diglotToggle.checked });
  });

  const { diglotWeave } = await chrome.storage.sync.get(['diglotWeave']);
  diglotToggle.checked = diglotWeave !== false;

  // Live-update while the background sync writes progress.
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && ('traverseSyncState' in changes || 'traverseAuth' in changes)) {
      renderTraverse();
    }
  });

  renderTraverse();
});
