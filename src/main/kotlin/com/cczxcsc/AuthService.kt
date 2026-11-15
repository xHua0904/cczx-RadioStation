package com.cczxcsc

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime

@Service
class AuthService(
    private val jdbc: JdbcTemplate, private val transactions: TransactionTemplate,
    @Value("\${app.admin-password:admin}") private val initialPassword: String,
    private val vault: PasswordVault
) {
    private val attempts = mutableMapOf<String, MutableList<Long>>()
    fun setting(key: String): String? = jdbc.queryForList("SELECT value FROM settings WHERE key=?", key).firstOrNull()?.get("value")?.toString()
    fun setSetting(key: String, value: String) {
        jdbc.update("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)
    }

    fun initialize() {
        if (setting("admin_hash") == null) {
            val password = initialPassword.ifBlank { Passwords.DEFAULT_ADMIN_PASSWORD }
            require(Passwords.validAdminPassword(password)) { "BROADCAST_ADMIN_PASSWORD 应为 admin 或 6–64 位密码" }
            transactions.executeWithoutResult {
                val inserted = jdbc.update("INSERT INTO settings(key,value) VALUES('admin_hash',?) ON CONFLICT(key) DO NOTHING", Passwords.hash(password))
                if (inserted == 1) { setSetting("admin_password_initial", "1"); setSetting("admin_password_encrypted", vault.encrypt(password)) }
            }
        }
    }

    fun resetPassword() {
        val password = Passwords.DEFAULT_ADMIN_PASSWORD
        transactions.executeWithoutResult {
            setSetting("admin_hash", Passwords.hash(password)); setSetting("admin_password_encrypted", vault.encrypt(password))
            setSetting("admin_password_initial", "1"); jdbc.update("DELETE FROM sessions")
        }
        println("管理员密码已重置为默认值 admin，全部登录会话已注销。")
    }

    fun currentPassword(): String {
        val hash = setting("admin_hash").orEmpty()
        setting("admin_password_encrypted")?.let { record ->
            val password = runCatching { vault.decrypt(record) }.getOrNull()
            if (password != null && Passwords.verify(password, hash)) return password
        }
        // 兼容只有哈希的旧库；未知旧密码无法从哈希还原，也不会被默认密码覆盖。
        listOf(Passwords.DEFAULT_ADMIN_PASSWORD, initialPassword).distinct().forEach { password ->
            if (password.isNotEmpty() && Passwords.verify(password, hash)) return password
        }
        throw IllegalStateException("当前密码无法查询：旧库仅有哈希，或密码查询密钥缺失。可使用 --reset-password 恢复默认密码 admin。")
    }

    fun login(password: String, ip: String): Map<String, Any> {
        if (password.length > 64) throw ApiError(400, "密码不能超过 64 位")
        synchronized(attempts) {
            val now = System.currentTimeMillis()
            attempts.values.forEach { it.removeIf { time -> now - time >= 600_000 } }
            attempts.entries.removeIf { it.value.isEmpty() }
            if ((attempts[ip]?.size ?: 0) >= 8) throw ApiError(429, "密码错误次数过多，请 10 分钟后再试")
        }
        if (!Passwords.verify(password, setting("admin_hash").orEmpty())) {
            synchronized(attempts) {
                if (attempts.size >= 10_000 && ip !in attempts) attempts.remove(attempts.keys.first())
                attempts.getOrPut(ip) { mutableListOf() }.add(System.currentTimeMillis())
            }
            throw ApiError(401, "密码不正确")
        }
        synchronized(attempts) { attempts.remove(ip) }
        val token = Passwords.token()
        val created = nowIso()
        val expires = OffsetDateTime.parse(created).plusHours(12).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        transactions.executeWithoutResult {
            jdbc.update("DELETE FROM sessions WHERE expires_at<?", created)
            jdbc.update("INSERT INTO sessions(token,created_at,expires_at) VALUES(?,?,?)", Passwords.digest(token), created, expires)
        }
        return mapOf("ok" to true, "token" to token, "expires_at" to expires, "initial_password" to (setting("admin_password_initial") == "1"))
    }

    fun requireAdmin(header: String?): String {
        val token = if (header?.startsWith("Bearer ", ignoreCase = true) == true) header.substring(7).trim() else ""
        if (token.isEmpty() || token.length > 256) throw ApiError(401, "请先登录")
        val hash = Passwords.digest(token)
        val expiry = jdbc.queryForList("SELECT expires_at FROM sessions WHERE token=?", hash).firstOrNull()?.get("expires_at")?.toString()
        if (expiry == null || expiry < nowIso()) throw ApiError(401, "登录已过期，请重新登录")
        return hash
    }

    fun logout(header: String?) { jdbc.update("DELETE FROM sessions WHERE token=?", requireAdmin(header)) }

    fun changePassword(old: String, new: String) {
        if (!Passwords.validAdminPassword(new)) throw ApiError(400, "新密码应为 admin 或 6–64 位密码")
        transactions.executeWithoutResult {
            if (!Passwords.verify(old, setting("admin_hash").orEmpty())) throw ApiError(400, "原密码不正确")
            setSetting("admin_hash", Passwords.hash(new)); setSetting("admin_password_encrypted", vault.encrypt(new))
            setSetting("admin_password_initial", "0"); jdbc.update("DELETE FROM sessions")
        }
    }
}
