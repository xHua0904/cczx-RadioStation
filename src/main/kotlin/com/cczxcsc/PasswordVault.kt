package com.cczxcsc

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// 登录仍使用哈希；本机查询命令使用加密记录，密钥与数据库分目录保存。
@Component
class PasswordVault(@Value("\${app.data-dir}") dataDir: String,
    @Value("\${app.secrets-dir:./secrets}") secretsDir: String = dataDir) {
    private val keyPath = Path.of(secretsDir, "admin-password.key").toAbsolutePath().normalize()
    private val oldKeyPath = Path.of(dataDir, "admin-password.key").toAbsolutePath().normalize()
    private val random = SecureRandom()

    private fun protect(path: Path, directory: Boolean = false) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            val permissions = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            if (directory) permissions += PosixFilePermission.OWNER_EXECUTE
            Files.setPosixFilePermissions(path, permissions)
        }
    }
    @Synchronized fun prepare() {
        if (keyPath == oldKeyPath || !Files.exists(oldKeyPath)) return
        Files.createDirectories(keyPath.parent)
        protect(keyPath.parent, true)
        val legacy = Files.readAllBytes(oldKeyPath)
        require(legacy.size == 32) { "旧密码查询密钥格式不正确" }
        try { Files.write(keyPath, legacy, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
        catch (_: FileAlreadyExistsException) { /* 重复升级时验证已有密钥，不覆盖其他实例的密钥。 */ }
        require(MessageDigest.isEqual(legacy, Files.readAllBytes(keyPath))) { "新旧密码查询密钥不同，已停止迁移，原密钥保持不变" }
        protect(keyPath)
        // 复制并校验后移除数据目录中的旧密钥；升级前应先停止所有旧版本实例。
        Files.delete(oldKeyPath)
    }

    private fun key(create: Boolean): SecretKeySpec {
        prepare()
        if (create && !Files.exists(keyPath)) {
            Files.createDirectories(keyPath.toAbsolutePath().parent)
            protect(keyPath.parent, true)
            val bytes = ByteArray(32).also(random::nextBytes)
            try { Files.write(keyPath, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
            catch (_: FileAlreadyExistsException) { /* 其他实例已创建密钥，读取同一份即可。 */ }
        }
        val bytes = Files.readAllBytes(keyPath)
        require(bytes.size == 32) { "密码查询密钥格式不正确" }
        protect(keyPath)
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
