package dev.yourstream.sync

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncPureTest {
    private fun r(file: String?, at: Long, pos: Long = 10, done: Boolean = false, key: String = "movie:vikram:2022") =
        ProgressRecord(file, key, null, null, pos, 100, done, 1, at)

    @Test
    fun encryptionRoundTripsAndRejectsTheWrongPassphraseOrTampering() {
        val key = SyncCrypto.deriveKey("correct horse".toCharArray(), 42, iterations = 1_000)
        val same = SyncCrypto.deriveKey("correct horse".toCharArray(), 42, iterations = 1_000)
        assertContentEquals(key, same, "every device of the account derives the same key")
        assertNotEquals(key.toList(), SyncCrypto.deriveKey("correct horse".toCharArray(), 43, iterations = 1_000).toList(), "another account, another key")

        val plain = "progress".toByteArray()
        val sealed = SyncCrypto.seal(key, plain, SecureRandom())
        assertFalse(sealed.decodeToString().contains("progress"), "Telegram only sees ciphertext")
        assertContentEquals(plain, SyncCrypto.open(key, sealed))
        assertNotEquals(sealed.toList(), SyncCrypto.seal(key, plain).toList(), "fresh nonce per snapshot")

        val wrong = SyncCrypto.deriveKey("wrong".toCharArray(), 42, iterations = 1_000)
        assertFailsWith<SyncCrypto.WrongKey> { SyncCrypto.open(wrong, sealed) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertFailsWith<SyncCrypto.WrongKey> { SyncCrypto.open(key, tampered) }
        assertFailsWith<SyncCrypto.NotASnapshot> { SyncCrypto.open(key, "hello".toByteArray()) }
    }

    @Test
    fun mergeKeepsTheNewestPerUnitAndUnitsOnlyOneSideHas() {
        // Tie: finished beats unfinished.
        assertTrue(ProgressMerge.newer(r("a", 5, done = false), r("a", 5, done = true)).completed)
        assertEquals(9L, ProgressMerge.newer(r("a", 9, pos = 1), r("a", 5, pos = 99)).at)

        val remote = listOf(r("a", 10, pos = 30), r("only-remote", 3))
        val local = listOf(r("a", 20, pos = 60), r(null, 7, key = "series:ben 10").copy(season = 1, episode = 2))
        val union = ProgressMerge.union(remote, local)
        assertEquals(3, union.size)
        assertEquals(60L, union.first { it.file == "a" }.positionMs, "the newer progress of the same file wins")
        assertTrue(union.any { it.file == "only-remote" }, "a file this device lacks is kept for the others")

        assertTrue(ProgressMerge.shouldApply(r("a", 10), null))
        assertFalse(ProgressMerge.shouldApply(r("a", 10), 10), "equal is not newer")
    }

    @Test
    fun snapshotFormatIsCompact() {
        val s = ProgressSnapshot(device = "Pixel 8", at = 1, records = listOf(r("AgADxyz", 5)))
        val text = s.encode().decodeToString()
        assertTrue("\"f\":\"AgADxyz\"" in text && "\"k\":" in text)
        assertEquals(s, ProgressSnapshot.decode(s.encode()))
    }
}
