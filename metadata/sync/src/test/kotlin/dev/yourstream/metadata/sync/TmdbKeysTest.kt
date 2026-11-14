package dev.yourstream.metadata.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.yourstream.metadata.tmdb.TmdbConfig
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the TMDB key is stored: encrypted, migrated from the old plain copy, and dropped when it
 * can no longer be decrypted. Robolectric has no Android Keystore, so the encryption here is a
 * stand-in [SecretBox]; [KeystoreBox] itself runs only on a device.
 */
@RunWith(RobolectricTestRunner::class)
class TmdbKeysTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE)
    private val key = "0123456789abcdef0123456789abcdef"

    /** Reversible, and visibly not the plain text. */
    private class FakeBox(var broken: Boolean = false) : SecretBox {
        override fun seal(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray): ByteArray {
            check(!broken) { "key lost" }
            return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @Before fun clear() { prefs.edit().clear().commit() }

    private fun stored(): String = prefs.all.values.joinToString(" ")

    @Test
    fun aSavedKeyIsStoredEncryptedAndReadBack() {
        val box = FakeBox()
        TmdbKeys(context, TmdbConfig(), box).store(key)
        assertFalse(key in stored(), "no plain copy on disk")
        assertTrue(prefs.contains(TmdbKeys.SEALED_KEY))
        val again = TmdbKeys(context, TmdbConfig(), box)
        assertEquals(key, again.userKey.value)
        assertEquals(TmdbKeySource.USER, again.source)
        assertEquals(key, again.current().apiKey)
    }

    @Test
    fun aPlainKeyFromAnOlderVersionIsEncryptedOnFirstRead() {
        prefs.edit().putString(TmdbKeys.PLAIN_KEY, key).commit()
        val keys = TmdbKeys(context, TmdbConfig(), FakeBox())
        assertEquals(key, keys.userKey.value)
        assertFalse(prefs.contains(TmdbKeys.PLAIN_KEY), "plain copy removed")
        assertFalse(key in stored())
        assertEquals(key, TmdbKeys(context, TmdbConfig(), FakeBox()).userKey.value)
    }

    @Test
    fun aKeyThatCannotBeDecryptedCountsAsNone() {
        TmdbKeys(context, TmdbConfig(), FakeBox()).store(key)
        val keys = TmdbKeys(context, TmdbConfig(), FakeBox(broken = true))
        assertNull(keys.userKey.value)
        assertEquals(TmdbKeySource.NONE, keys.source)
    }

    @Test
    fun removingTheKeyRemovesItFromDisk() {
        val keys = TmdbKeys(context, TmdbConfig(), FakeBox())
        keys.store(key)
        keys.store(null)
        assertTrue(prefs.all.isEmpty())
        assertNull(TmdbKeys(context, TmdbConfig(), FakeBox()).userKey.value)
    }
}
