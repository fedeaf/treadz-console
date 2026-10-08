package com.treadz.console.sync

import android.content.Context
import com.treadz.console.sync.token.EncryptedTokenStore
import com.treadz.console.sync.token.InMemoryTokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The token store with a software AES key (the Android Keystore itself only exists on a device; production uses
 * `AndroidKeystoreKeys`, whose only job is to hand over a key).
 */
@RunWith(RobolectricTestRunner::class)
class TokenStoreTest {

    private val prefs = (RuntimeEnvironment.getApplication() as Context).getSharedPreferences("test_secrets", Context.MODE_PRIVATE)

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `the token round trips and is never stored in clear`() {
        val key = newKey()
        val store = EncryptedTokenStore(prefs) { key }
        assertNull(store.get())
        store.set(TOKEN)
        assertEquals(TOKEN, store.get())
        assertEquals(TOKEN, EncryptedTokenStore(prefs) { key }.get()) // a new instance (restart) reads it
        val stored = prefs.all.values.joinToString()
        assertFalse(stored.contains(TOKEN))
        assertFalse(stored.contains("SECRET"))
        store.clear()
        assertNull(store.get())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun `every encryption uses a fresh nonce`() {
        val key = newKey()
        val store = EncryptedTokenStore(prefs) { key }
        store.set(TOKEN)
        val first = prefs.all.values.single()
        store.set(TOKEN)
        assertFalse(first == prefs.all.values.single())
    }

    @Test fun `a lost key or a damaged value means no token, not a crash`() {
        EncryptedTokenStore(prefs) { newKey() }.set(TOKEN)
        val other = EncryptedTokenStore(prefs) { newKey() } // e.g. restored from a backup on another device
        assertNull(other.get())
        assertTrue("the unusable value is dropped", prefs.all.isEmpty())

        prefs.edit().putString("github_token", "not:valid!").apply()
        assertNull(EncryptedTokenStore(prefs) { newKey() }.get())
    }

    @Test fun `stores do not print the token`() {
        val memory = InMemoryTokenStore(TOKEN)
        assertFalse(memory.toString().contains(TOKEN))
        val key = newKey()
        val encrypted = EncryptedTokenStore(prefs) { key }.also { it.set(TOKEN) }
        assertFalse(encrypted.toString().contains(TOKEN))
    }
}
