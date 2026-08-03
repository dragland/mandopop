package com.mandopop.traverse

import android.content.Context
import com.mandopop.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Firebase Auth against the Traverse project over REST.
 *
 * The user's password is used exactly once, to exchange for a refresh token, and is never
 * persisted. From then on the refresh token, sealed by Tink, mints short-lived ID tokens.
 */
class TraverseAuth private constructor(context: Context) {
    private val secrets = SecretStore(context)

    /** Token and its expiry as one immutable value, so the pair can never be read half-updated. */
    private data class Token(val value: String, val expiresAtMs: Long)

    data class Identity(val uid: String, val email: String)

    @Volatile
    private var identity: Identity? = null

    /**
     * Resolved once and cached. Reading it decrypts through Tink and touches disk, so it is a
     * suspending call — callers must not need the answer synchronously during composition.
     */
    private suspend fun identity(): Identity? {
        identity?.let { return it }
        val uid = secrets.get(KEY_UID) ?: return null
        return Identity(uid, secrets.get(KEY_EMAIL).orEmpty()).also { identity = it }
    }

    suspend fun isSignedIn(): Boolean = identity() != null

    suspend fun uid(): String? = identity()?.uid

    suspend fun email(): String? = identity()?.email

    /** Exchanges email+password for a refresh token. The password is not retained. */
    suspend fun signIn(email: String, password: String) {
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("email", email)
                .put("password", password)
                .put("returnSecureToken", true)
                .toString()

            val response = try {
                Http.postJson("$IDENTITY_BASE/accounts:signInWithPassword?key=$API_KEY", body)
            } catch (error: TraverseException) {
                throw TraverseException(friendlySignInError(error.message.orEmpty()), error)
            }

            val json = JSONObject(response)
            val refreshToken = json.optString("refreshToken").ifBlank {
                throw TraverseException("Sign-in response had no refreshToken")
            }
            val localId = json.optString("localId").ifBlank {
                throw TraverseException("Sign-in response had no localId")
            }

            secrets.put(KEY_REFRESH_TOKEN, refreshToken)
            secrets.put(KEY_UID, localId)
            secrets.put(KEY_EMAIL, email)
            identity = Identity(localId, email)

            cached = json.optString("idToken").takeIf { it.isNotBlank() }?.let {
                Token(it, expiryFromSeconds(json.optString("expiresIn")))
            }
        }
    }

    suspend fun signOut() {
        cached = null
        identity = null
        secrets.clear()
    }

    /** Returns a valid ID token, refreshing if the cached one is missing or near expiry. */
    suspend fun idToken(): String {
        usableToken()?.let { return it }
        return refreshMutex.withLock {
            usableToken() ?: refresh()
        }
    }

    private fun usableToken(): String? {
        val token = cached ?: return null
        return token.value.takeIf { System.currentTimeMillis() < token.expiresAtMs - EXPIRY_SKEW_MS }
    }

    private suspend fun refresh(): String {
        val refreshToken = secrets.get(KEY_REFRESH_TOKEN)
            ?: throw TraverseException("Not signed in to Traverse")

        return withContext(Dispatchers.IO) {
            val body = "grant_type=refresh_token&refresh_token=" +
                URLEncoder.encode(refreshToken, "UTF-8")

            val response = try {
                Http.postForm("$SECURE_TOKEN_BASE/token?key=$API_KEY", body)
            } catch (error: TraverseException) {
                // A rejected refresh token is terminal — force re-auth rather than retrying forever.
                if (error.statusCode == 400) {
                    signOut()
                    throw TraverseException(
                        "Traverse session expired — sign in again",
                        error,
                        statusCode = 400,
                    )
                }
                throw error
            }

            val json = JSONObject(response)
            val idToken = json.optString("id_token").ifBlank {
                throw TraverseException("Refresh response had no id_token")
            }
            json.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
                secrets.put(KEY_REFRESH_TOKEN, it)
            }
            cached = Token(idToken, expiryFromSeconds(json.optString("expires_in")))
            idToken
        }
    }

    private fun expiryFromSeconds(raw: String): Long {
        val seconds = raw.toLongOrNull() ?: DEFAULT_TTL_SECONDS
        return System.currentTimeMillis() + seconds * 1000L
    }

    private fun friendlySignInError(raw: String): String = when {
        raw.contains("INVALID_LOGIN_CREDENTIALS") || raw.contains("INVALID_PASSWORD") ->
            "Traverse rejected that email or password"
        raw.contains("EMAIL_NOT_FOUND") -> "No Traverse account for that email"
        raw.contains("TOO_MANY_ATTEMPTS") -> "Too many attempts — wait and try again"
        raw.contains("MFA") || raw.contains("SECOND_FACTOR") ->
            "This account uses two-factor auth, which mandopop does not support yet"
        else -> "Traverse sign-in failed: $raw"
    }

    companion object {
        /**
         * Traverse's Firebase project, from build config. A Firebase web API key identifies a
         * project; it is not a credential and grants nothing on its own. Access is gated by
         * Firestore rules against the signed-in user, so the only secrets are the password (never
         * stored) and the refresh token (encrypted on-device).
         */
        val API_KEY: String get() = BuildConfig.TRAVERSE_API_KEY
        val PROJECT_ID: String get() = BuildConfig.TRAVERSE_PROJECT_ID

        private val refreshMutex = Mutex()

        /**
         * Token cache is process-wide, not per-instance: the UI and the worker each build their
         * own [TraverseSync], and a per-instance cache would mean a refresh on every worker run
         * (~96/day) instead of once per token lifetime (~24/day).
         */
        @Volatile
        private var cached: Token? = null

        @Volatile
        private var instance: TraverseAuth? = null

        fun get(context: Context): TraverseAuth {
            return instance ?: synchronized(this) {
                instance ?: TraverseAuth(context).also { instance = it }
            }
        }

        private const val IDENTITY_BASE = "https://identitytoolkit.googleapis.com/v1"
        private const val SECURE_TOKEN_BASE = "https://securetoken.googleapis.com/v1"
        private const val DEFAULT_TTL_SECONDS = 3600L
        private const val EXPIRY_SKEW_MS = 5 * 60 * 1000L

        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_UID = "uid"
        private const val KEY_EMAIL = "email"
    }
}
