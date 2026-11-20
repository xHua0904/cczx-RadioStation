package com.cczxcsc

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    const val DEFAULT_ADMIN_PASSWORD = "cczx123456"
    fun validAdminPassword(password: String): Boolean = password == DEFAULT_ADMIN_PASSWORD || password.length in 6..64
    private val random = SecureRandom()
    fun token(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun hash(password: String, salt: ByteArray = ByteArray(16).also(random::nextBytes), rounds: Int = 120_000): String {
        val key = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
        val digest = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).encoded } finally { key.clearPassword() }
        return "pbkdf2_sha256\$$rounds\$${salt.toHex()}\$${digest.toHex()}"
    }
    fun verify(password: String, stored: String): Boolean = runCatching {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return false
        val rounds = parts[1].toInt()
        if (rounds !in 10_000..2_000_000 || parts[2].length != 32 || parts[3].length != 64) return false
        MessageDigest.isEqual(hash(password, parts[2].hexBytes(), rounds).toByteArray(), stored.toByteArray())
    }.getOrDefault(false)
    fun digest(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toHex()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
