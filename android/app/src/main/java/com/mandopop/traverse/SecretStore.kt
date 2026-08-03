package com.mandopop.traverse

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.secretsDataStore by preferencesDataStore(name = "traverse_secrets")

/**
 * Encrypted storage for the Traverse refresh token.
 *
 * Tink owns the cryptography rather than this file: it picks the AEAD, manages the keyset, and is
 * built to resist the misuse that makes hand-written `Cipher` code risky. The keyset itself is
 * sealed by an Android Keystore master key, so the material never leaves the TEE.
 *
 * The pairing is deliberate. `EncryptedSharedPreferences` is deprecated — it wedged crypto into a
 * synchronous, main-thread API and broke on some OEMs' Keystore implementations — and DataStore
 * plus Tink is the split Google points to instead: async persistence, dedicated crypto.
 */
class SecretStore(context: Context) {
    private val appContext = context.applicationContext

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        val stored = appContext.secretsDataStore.data.first()[stringPreferencesKey(key)]
            ?: return@withContext null
        try {
            String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), associatedData(key)))
        } catch (error: Exception) {
            // Undecryptable means the keyset was lost or replaced — app data restored to another
            // device, keystore reset. Drop it so the caller sees "not signed in" instead of
            // failing forever on a value that can never be read again.
            put(key, null)
            null
        }
    }

    suspend fun put(key: String, value: String?) = withContext(Dispatchers.IO) {
        val preferenceKey = stringPreferencesKey(key)
        appContext.secretsDataStore.edit { preferences ->
            if (value == null) {
                preferences.remove(preferenceKey)
            } else {
                val sealed = aead.encrypt(value.toByteArray(), associatedData(key))
                preferences[preferenceKey] = Base64.encodeToString(sealed, Base64.NO_WRAP)
            }
        }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        appContext.secretsDataStore.edit { it.clear() }
        Unit
    }

    /** Binds each ciphertext to its key, so a value cannot be swapped between entries. */
    private fun associatedData(key: String) = key.toByteArray()

    private companion object {
        const val KEYSET_NAME = "traverse_keyset"
        const val KEYSET_PREFS = "mandopop_traverse_keyset"
        const val MASTER_KEY_URI = "android-keystore://mandopop_traverse_master"
    }
}
