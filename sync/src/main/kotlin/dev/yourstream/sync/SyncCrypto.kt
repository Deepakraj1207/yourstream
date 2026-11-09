package dev.yourstream.sync

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for sync snapshots: only devices that know the sync passphrase can read
 * them; Telegram (which stores them) sees random bytes.
 *
 * * Key: PBKDF2-HMAC-SHA256 over the passphrase, salted with the Telegram account id (so every
 *   device of the account derives the same key, and the same passphrase on another account gives
 *   another key), 210 000 iterations, 256 bits.
 * * Data: AES-256-GCM, random 96-bit nonce per snapshot, the format header as associated data.
 *   Format: `YSYNC1` ‖ nonce(12) ‖ ciphertext+tag.
 */
object SyncCrypto {
    private val MAGIC = "YSYNC1".toByteArray()
    private const val NONCE = 12
    private const val TAG_BITS = 128
    const val ITERATIONS = 210_000

    class WrongKey : Exception("The sync passphrase does not match the one used on your other devices")
    class NotASnapshot : Exception("Not a YourStream sync file")

    fun deriveKey(passphrase: CharArray, accountId: Long, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(passphrase, "yourstream-sync:$accountId".toByteArray(), iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun seal(key: ByteArray, plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC)
        return MAGIC + nonce + cipher.doFinal(plain)
    }

    /** @throws WrongKey if [key] is not the key it was sealed with (or the data was altered). */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < MAGIC.size + NONCE + TAG_BITS / 8 || !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotASnapshot()
        val nonce = sealed.copyOfRange(MAGIC.size, MAGIC.size + NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC)
        return try {
            cipher.doFinal(sealed, MAGIC.size + NONCE, sealed.size - MAGIC.size - NONCE)
        } catch (e: AEADBadTagException) {
            throw WrongKey()
        }
    }
}
