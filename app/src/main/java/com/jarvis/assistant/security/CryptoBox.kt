package com.jarvis.assistant.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypts values before they reach Room. */
interface SecretCipher {
    fun encrypt(plainText: String): String
    /** Returns null when the value cannot be decrypted (for example after a key reset). */
    fun decrypt(cipherText: String): String?
}

/**
 * AES-256-GCM with a key that never leaves the Android Keystore. Memory, task titles
 * and routine steps are stored encrypted, and the database file alone is useless
 * without the device's keystore.
 */
@Singleton
class CryptoBox @Inject constructor() : SecretCipher {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // Store IV with the ciphertext so the value is self-contained.
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String? = runCatching {
        val combined = Base64.decode(cipherText, Base64.NO_WRAP)
        if (combined.size <= IV_LENGTH) return null
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val payload = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }.getOrNull()

    private fun obtainKey(): SecretKey {
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
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "jarvis.memory.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
