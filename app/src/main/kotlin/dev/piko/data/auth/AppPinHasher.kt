package dev.piko.data.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Android 应用锁的 4 位 PIN 摘要。
 *
 * PIN 本身只有一万种组合，不能明文或直接哈希后放进 DataStore。这里用随机盐加 PBKDF2，
 * 让拿到偏好文件的人也必须逐次尝试；真正的使用边界仍是设备上的应用私有目录。
 */
internal object AppPinHasher {
    const val PIN_LENGTH = 4

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val VERSION = "v1"
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    fun isWellFormed(pin: String): Boolean =
        pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    fun hash(pin: String): String {
        require(isWellFormed(pin)) { "PIN must be four digits" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val digest = derive(pin, salt, ITERATIONS)
        return listOf(VERSION, ITERATIONS.toString(), encode(salt), encode(digest)).joinToString(":")
    }

    fun verify(pin: String, stored: String): Boolean {
        if (!isWellFormed(pin)) return false
        val parts = stored.split(':')
        if (parts.size != 4 || parts[0] != VERSION) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = decodeOrNull(parts[2]) ?: return false
        val expected = decodeOrNull(parts[3]) ?: return false
        return MessageDigest.isEqual(expected, derive(pin, salt, iterations))
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodeOrNull(text: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}
