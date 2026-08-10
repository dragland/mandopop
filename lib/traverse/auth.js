/**
 * Traverse (Firebase) authentication — port of android TraverseAuth.kt.
 *
 * Email/password sign-in against identitytoolkit, ID-token refresh against
 * securetoken. The refresh token is persisted in chrome.storage.local; the
 * password never is. The ID-token cache is persisted too, because a
 * service worker is evicted between wakes and re-minting a token on every
 * wake would hit securetoken far more often than the phone does.
 */

// Traverse's public Firebase client key — the same one its web app and the
// android build ship. Identifies the project; it is not a secret.
const API_KEY = 'AIzaSyAsG5pbllBxykmI8Gd94-zwB0WouEVg6y0';
export const PROJECT_ID = 'alley-d0944';

const IDENTITY_BASE = 'https://identitytoolkit.googleapis.com/v1';
const SECURE_TOKEN_BASE = 'https://securetoken.googleapis.com/v1';

const EXPIRY_SKEW_MS = 5 * 60 * 1000;
const DEFAULT_TTL_SECONDS = 3600;

const AUTH_KEY = 'traverseAuth';
const TOKEN_KEY = 'traverseToken';

export class TraverseError extends Error {
  constructor(message, statusCode = null) {
    super(message);
    this.statusCode = statusCode;
  }
}

// Single-flight refresh across concurrent callers within one worker life.
let refreshing = null;

async function readLocal(key) {
  const stored = await chrome.storage.local.get(key);
  return stored[key] ?? null;
}

function friendlySignInError(body) {
  if (body.includes('INVALID_LOGIN_CREDENTIALS') || body.includes('INVALID_PASSWORD')) {
    return 'Wrong email or password';
  }
  if (body.includes('EMAIL_NOT_FOUND')) return 'No Traverse account with that email';
  if (body.includes('TOO_MANY_ATTEMPTS')) return 'Too many attempts — try again later';
  if (body.includes('MFA') || body.includes('SECOND_FACTOR')) {
    return 'This account uses two-factor auth, which is not supported';
  }
  return null;
}

export async function signIn(email, password) {
  const response = await fetch(`${IDENTITY_BASE}/accounts:signInWithPassword?key=${API_KEY}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  });
  const body = await response.text();
  if (!response.ok) {
    throw new TraverseError(
      friendlySignInError(body) ?? `HTTP ${response.status} from sign-in: ${body.slice(0, 400)}`,
      response.status,
    );
  }

  const json = JSON.parse(body);
  if (!json.refreshToken || !json.localId) {
    throw new TraverseError('Sign-in response missing refreshToken or localId');
  }

  await chrome.storage.local.set({
    [AUTH_KEY]: { refreshToken: json.refreshToken, uid: json.localId, email },
    [TOKEN_KEY]: json.idToken
      ? {
          token: json.idToken,
          expiresAtMs: Date.now() + (Number(json.expiresIn) || DEFAULT_TTL_SECONDS) * 1000,
        }
      : null,
  });
  return { uid: json.localId, email };
}

export async function signOut() {
  await chrome.storage.local.remove([AUTH_KEY, TOKEN_KEY]);
}

/** @returns {Promise<{uid: string, email: string}|null>} */
export async function account() {
  const auth = await readLocal(AUTH_KEY);
  return auth ? { uid: auth.uid, email: auth.email } : null;
}

async function refresh(refreshToken) {
  const response = await fetch(`${SECURE_TOKEN_BASE}/token?key=${API_KEY}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=refresh_token&refresh_token=${encodeURIComponent(refreshToken)}`,
  });
  const body = await response.text();
  if (response.status === 400) {
    // The refresh token is dead — a retry can never succeed.
    await signOut();
    throw new TraverseError('Traverse session expired — sign in again', 400);
  }
  if (!response.ok) {
    throw new TraverseError(`HTTP ${response.status} from token refresh`, response.status);
  }

  const json = JSON.parse(body);
  const token = {
    token: json.id_token,
    expiresAtMs: Date.now() + (Number(json.expires_in) || DEFAULT_TTL_SECONDS) * 1000,
  };
  const updates = { [TOKEN_KEY]: token };
  if (json.refresh_token && json.refresh_token !== refreshToken) {
    const auth = await readLocal(AUTH_KEY);
    if (auth) updates[AUTH_KEY] = { ...auth, refreshToken: json.refresh_token };
  }
  await chrome.storage.local.set(updates);
  return token.token;
}

/** Current ID token, refreshing if within the expiry skew. Throws when signed out. */
export async function idToken() {
  const auth = await readLocal(AUTH_KEY);
  if (!auth) throw new TraverseError('Not signed in to Traverse');

  const cached = await readLocal(TOKEN_KEY);
  if (cached && Date.now() < cached.expiresAtMs - EXPIRY_SKEW_MS) return cached.token;

  refreshing ??= refresh(auth.refreshToken).finally(() => {
    refreshing = null;
  });
  return refreshing;
}
