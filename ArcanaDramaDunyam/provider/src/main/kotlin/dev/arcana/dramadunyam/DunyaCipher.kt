package dev.arcana.dramadunyam

import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Published ShortMax fragment container. Each response carries its own key in its header;
 * this reads that normal wire format and never creates a publisher credential or signature. */
internal object DunyaCipher {
    private val magic = "shortmax".toByteArray(Charsets.US_ASCII)
    private val iv = "shortmax00000000".toByteArray(Charsets.US_ASCII)
    private const val header = 1024
    fun hasMagic(bytes: ByteArray): Boolean = bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
    private fun invalid(reason: String): Nothing = throw IOException("DramaDünyam segment format: $reason")
    fun decode(bytes: ByteArray): ByteArray? {
        if (!hasMagic(bytes)) return null
        if (bytes.size < header + 16) invalid("short")
        if ((0 until header).any { (bytes[it].toInt() and 255) !in 0x20..0x7e }) invalid("header")
        fun digits(start: Int): Int {
            var number = 0
            for (index in start until start + 4) {
                val digit = (bytes[index].toInt() and 255) - 0x30
                if (digit !in 0..9) invalid("number")
                number = number * 10 + digit
            }
            return number
        }
        val offset = digits(16)
        val length = digits(20)
        if (offset !in 24..(header - 16)) invalid("key offset")
        if (length < 16 || length % 16 != 0 || header + length > bytes.size) invalid("length")
        val key = bytes.copyOfRange(offset, offset + 16)
        val plain = try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(DunyaCipher.iv))
                doFinal(bytes, header, length)
            }
        } catch (_: Exception) { invalid("decrypt") }
        finally { key.fill(0) }
        if (plain.isEmpty() || plain[0] != 0x47.toByte()) invalid("sync")
        val rest = header + length
        return ByteArray(plain.size + bytes.size - rest).also {
            plain.copyInto(it)
            bytes.copyInto(it, plain.size, rest)
        }
    }
}
