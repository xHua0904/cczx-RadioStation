package com.cczxcsc.security

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

// 登录仍使用哈希；本机查询命令使用加密记录和数据目录中的独立密钥。
@Component
class PasswordVault(@Value("\${app.data-dir}") dataDir: String) {
    private val keyPath = Path.of(dataDir, "admin-password.key")
    private val random = SecureRandom()

    private fun key(create: Boolean): SecretKeySpec {
        if (create && !Files.exists(keyPath)) {
            Files.createDirectories(keyPath.toAbsolutePath().parent)
            val bytes = ByteArray(32).also(random::nextBytes)
            try { Files.write(keyPath, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
            catch (_: FileAlreadyExistsException) { /* 其他实例已创建密钥，读取同一份即可。 */ }
        }
        val bytes = Files.readAllBytes(keyPath)
        require(bytes.size == 32) { "密码查询密钥格式不正确" }
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(password: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true), GCMParameterSpec(128, nonce))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(record: String): String {
        val bytes = Base64.getDecoder().decode(record)
        require(bytes.size in 28..1024) { "密码查询记录格式不正确" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
