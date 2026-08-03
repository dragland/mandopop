package com.mandopop.traverse

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small string values encrypted with an AndroidKeyStore AES key.
 *
 * Hand-rolled instead of androidx.security-crypto because that library is deprecated and this
 * needs exactly one string round-trip. The AES key is generated in and never leaves the keystore;
 * only IV+ciphertext is written to SharedPreferences.
 */
class KeystoreSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * @param commit flush synchronously. Use for values whose loss forces a re-login — `apply()`
     *   can lose the write if the process dies before it lands.
     */
    fun put(key: String, value: String?, commit: Boolean = false) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + ciphertext
            editor.putString(key, Base64.encodeToString(packed, Base64.NO_WRAP))
        }
        if (commit) editor.commit() else editor.apply()
    }

    fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            if (packed.size <= IV_LENGTH) return null
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: Exception) {
            // Key invalidated (e.g. app data restored to a new device). Drop the unreadable value
            // so the caller falls back to "not signed in" instead of crashing.
            prefs.edit().remove(key).apply()
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Synchronized because two threads racing here would both generate under the same alias, and
     * the loser's ciphertext would then fail GCM authentication — surfacing as an unexplained
     * sign-out that is impossible to diagnose from a device.
     */
    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "mandopop_traverse_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mandopop_traverse_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }
}
