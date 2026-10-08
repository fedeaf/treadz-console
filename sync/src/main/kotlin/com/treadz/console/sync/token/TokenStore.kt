package com.treadz.console.sync.token

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the GitHub token lives on the device. The token is a secret: implementations keep it encrypted at rest, and
 * nothing in this module prints it or puts it in a message.
 */
interface TokenStore {
    /** The stored token, or `null` if there is none (or it can no longer be decrypted). */
    fun get(): String?

    fun set(token: String)

    fun clear()
}

/** [TokenStore] in memory, for tests and fakes. */
class InMemoryTokenStore(initial: String? = null) : TokenStore {
    @Volatile private var token: String? = initial

    override fun get(): String? = token

    override fun set(token: String) {
        this.token = token
    }

    override fun clear() {
        token = null
    }

    override fun toString(): String = "InMemoryTokenStore(${if (token == null) "empty" else "set"})"
}

/**
 * [TokenStore] that encrypts the token with AES-256-GCM before writing it to [prefs]. The key comes from [keys];
 * [AndroidKeystoreKeys] keeps it inside the Android Keystore, so it never leaves the secure hardware/OS storage and
 * is not part of backups.
 */
class EncryptedTokenStore(
    private val prefs: SharedPreferences,
    private val keys: KeyProvider = AndroidKeystoreKeys(),
) : TokenStore {

    fun interface KeyProvider {
        /** The AES key, created on first use. */
        fun key(): SecretKey
    }

    override fun get(): String? {
        val stored = prefs.getString(KEY, null) ?: return null
        return try {
            val (iv, data) = stored.split(SEPARATOR).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            // The key was lost (e.g. restored on another device) or the value is damaged: as if there were no token.
            prefs.edit().remove(KEY).apply()
            null
        }
    }

    override fun set(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key())
        val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + SEPARATOR + Base64.encodeToString(data, Base64.NO_WRAP)
        prefs.edit().putString(KEY, stored).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    override fun toString(): String = "EncryptedTokenStore"

    private companion object {
        const val KEY = "github_token"
        const val SEPARATOR = ":"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/** The AES key of [EncryptedTokenStore], generated inside the Android Keystore. */
class AndroidKeystoreKeys(private val alias: String = "treadz_sync_token_key") : EncryptedTokenStore.KeyProvider {

    override fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
