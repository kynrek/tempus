package com.eddyizm.tempus.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.eddyizm.tempus.model.Server
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts saved sign-in passwords (the server list and the signed-in password) with an AES key kept
 * in the Android Keystore: the key never leaves the device's secure key storage and only this app can
 * use it, so the stored values are useless if copied off the device.
 *
 * Stored form: "ks1:" + base64(iv) + ":" + base64(ciphertext). Values without the prefix are older,
 * plain-text saves; they still read, and [migrate] encrypts them.
 */
object CredentialCipher {
    private const val TAG = "CredentialCipher"
    private const val ALIAS = "tempus_credentials"
    private const val PREFIX = "ks1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generateKey()
            }
            cachedKey = key
            return key
        }
    }

    @JvmStatic
    fun isEncrypted(value: String?): Boolean = value != null && value.startsWith(PREFIX)

    /** Encrypts a password for storage; empty and already-encrypted values pass through. */
    @JvmStatic
    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty() || isEncrypted(plain)) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't encrypt; keeping the value as it was", e)
            plain
        }
    }

    /** The stored value read back: decrypted, or as is when it was saved before encryption. Null if unreadable. */
    @JvmStatic
    fun decrypt(stored: String?): String? {
        if (stored == null || !isEncrypted(stored)) return stored
        return try {
            val parts = stored.substring(PREFIX.length).split(":", limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val sealed = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (e: Exception) {
            // E.g. restored onto another device, whose keystore doesn't have this key: sign in again.
            Log.w(TAG, "Couldn't decrypt a saved password", e)
            null
        }
    }

    @JvmStatic
    fun encrypted(server: Server): Server = server.copy(password = encrypt(server.password) ?: "")

    @JvmStatic
    fun decrypted(server: Server): Server =
        if (isEncrypted(server.password)) server.copy(password = decrypt(server.password) ?: "") else server
}
