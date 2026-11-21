package com.cczxcsc

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime

@Service
class AuthService(
    private val jdbc: JdbcTemplate, private val transactions: TransactionTemplate,
    private val vault: PasswordVault, private val audit: AuditService
) {
    companion object { const val ADMIN_ROLE = "admin" }
    private val attempts = mutableMapOf<String, MutableList<Long>>()
    fun setting(key: String): String? = jdbc.queryForList("SELECT value FROM settings WHERE key=?", key).firstOrNull()?.get("value")?.toString()
    fun setSetting(key: String, value: String) {
        jdbc.update("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)
    }
    private fun account(username: String) = jdbc.queryForList("SELECT * FROM accounts WHERE username=?", username).firstOrNull()
    private fun name(username: String): String {
        val cleaned = username.trim()
        if (cleaned.isEmpty() || cleaned.length > 40 || cleaned.any { it.isISOControl() }) throw ApiError(400, "账号应为 1–40 个字符，不能包含控制字符")
        return cleaned
    }

    // 仅由本机管理命令调用，网页和 HTTP API 不提供创建账号的入口。
    fun addAccount(username: String) {
        val user = name(username)
        transactions.executeWithoutResult {
            val inserted = jdbc.update("INSERT INTO accounts(username,password_hash,password_encrypted,initial_password,created_at) VALUES(?,?,?,1,?) ON CONFLICT(username) DO NOTHING",
                user, Passwords.hash(Passwords.DEFAULT_ADMIN_PASSWORD), vault.encrypt(Passwords.DEFAULT_ADMIN_PASSWORD), nowIso())
            if (inserted != 1) throw ApiError(409, "账号已存在，已有密码保持不变")
            audit.record(AuditService.commandActor, "添加账号", "account", user, user)
        }
        println("已添加账号：$user；默认密码：${Passwords.DEFAULT_ADMIN_PASSWORD}")
    }

    fun listAccounts(): List<String> = jdbc.queryForList("SELECT username FROM accounts ORDER BY created_at,username", String::class.java)

    fun deleteAccount(username: String) {
        val user = name(username)
        transactions.executeWithoutResult {
            if (account(user) == null) throw ApiError(404, "账号不存在")
            jdbc.update("DELETE FROM sessions WHERE username=?", user)
            jdbc.update("DELETE FROM accounts WHERE username=?", user)
            audit.record(AuditService.commandActor, "删除账号", "account", user, user)
        }
        // 保留历史投稿、公告署名和操作记录，不将删除账号扩展为删除业务数据。
        println("已删除账号：$user，该账号的登录会话已注销。")
    }

    fun resetPassword(username: String) {
        val user = name(username)
        transactions.executeWithoutResult {
            val updated = jdbc.update("UPDATE accounts SET password_hash=?,password_encrypted=?,initial_password=1 WHERE username=?",
                Passwords.hash(Passwords.DEFAULT_ADMIN_PASSWORD), vault.encrypt(Passwords.DEFAULT_ADMIN_PASSWORD), user)
            if (updated != 1) throw ApiError(404, "账号不存在")
            jdbc.update("DELETE FROM sessions WHERE username=?", user)
            audit.record(AuditService.commandActor, "重置密码", "account", user, user)
        }
        println("账号 $user 的密码已重置为 ${Passwords.DEFAULT_ADMIN_PASSWORD}，该账号的登录会话已注销。")
    }

    fun currentPassword(username: String): String {
        val user = account(name(username)) ?: throw ApiError(404, "账号不存在")
        val hash = user["password_hash"].toString()
        val password = runCatching { vault.decrypt(user["password_encrypted"].toString()) }.getOrNull()
        if (password != null && Passwords.verify(password, hash)) {
            audit.record(AuditService.commandActor, "查询密码", "account", username, username)
            return password
        }
        // 旧库仅有哈希时只能识别已知默认值，不覆盖用户已修改的密码。
        listOf(Passwords.DEFAULT_ADMIN_PASSWORD).forEach {
            if (it.isNotEmpty() && Passwords.verify(it, hash)) {
                audit.record(AuditService.commandActor, "查询密码", "account", username, username)
                return it
            }
        }
        throw IllegalStateException("当前密码无法查询：旧库仅有哈希，或密码查询密钥缺失。可使用 --reset-password --account=账号 恢复默认密码。")
    }

    fun login(password: String, ip: String, username: String): Map<String, Any> {
        val userName = name(username)
        if (password.length > 64) throw ApiError(400, "密码不能超过 64 位")
        synchronized(attempts) {
            val now = System.currentTimeMillis()
            attempts.values.forEach { it.removeIf { time -> now - time >= 600_000 } }
            attempts.entries.removeIf { it.value.isEmpty() }
            if ((attempts[ip]?.size ?: 0) >= 8) throw ApiError(429, "密码错误次数过多，请 10 分钟后再试")
        }
        val user = account(userName)
        if (!Passwords.verify(password, user?.get("password_hash")?.toString().orEmpty())) {
            synchronized(attempts) {
                if (attempts.size >= 10_000 && ip !in attempts) attempts.remove(attempts.keys.first())
                attempts.getOrPut(ip) { mutableListOf() }.add(System.currentTimeMillis())
            }
            throw ApiError(401, "账号或密码不正确")
        }
        synchronized(attempts) { attempts.remove(ip) }
        val token = Passwords.token()
        val created = nowIso()
        val expires = OffsetDateTime.parse(created).plusHours(12).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        transactions.executeWithoutResult {
            jdbc.update("DELETE FROM sessions WHERE expires_at<?", created)
            val inserted = jdbc.update("INSERT INTO sessions(token,created_at,expires_at,username) SELECT ?,?,?,username FROM accounts WHERE username=? AND password_hash=?",
                Passwords.digest(token), created, expires, userName, user!!["password_hash"])
            if (inserted != 1) throw ApiError(401, "密码已更新，请重新登录")
            audit.record(userName, "登录后台", "account", userName, userName)
        }
        // 所有可登录账号均为最高管理员，不依赖旧数据库中可能存在的 role 字段。
        return mapOf("ok" to true, "token" to token, "expires_at" to expires, "username" to userName, "role" to ADMIN_ROLE,
            "initial_password" to ((user!!["initial_password"] as Number).toInt() == 1))
    }

    fun requireAdmin(header: String?): String {
        val token = if (header?.startsWith("Bearer ", ignoreCase = true) == true) header.substring(7).trim() else ""
        if (token.isEmpty() || token.length > 256) throw ApiError(401, "请先登录")
        val hash = Passwords.digest(token)
        val expiry = jdbc.queryForList("SELECT s.expires_at FROM sessions s JOIN accounts a ON a.username=s.username WHERE s.token=?", hash).firstOrNull()?.get("expires_at")?.toString()
        if (expiry == null || expiry < nowIso()) throw ApiError(401, "登录已过期，请重新登录")
        return hash
    }

    fun username(header: String?): String = jdbc.queryForObject("SELECT username FROM sessions WHERE token=?", String::class.java, requireAdmin(header))!!
    fun logout(header: String?) {
        transactions.executeWithoutResult {
            val user = username(header)
            jdbc.update("DELETE FROM sessions WHERE token=?", requireAdmin(header))
            audit.record(user, "退出后台", "account", user, user)
        }
    }

    fun changePassword(old: String, new: String, username: String) {
        val user = name(username)
        if (!Passwords.validAdminPassword(new)) throw ApiError(400, "新密码应为 6–64 位")
        transactions.executeWithoutResult {
            val record = account(user) ?: throw ApiError(404, "账号不存在")
            if (!Passwords.verify(old, record["password_hash"].toString())) throw ApiError(400, "原密码不正确")
            val updated = jdbc.update("UPDATE accounts SET password_hash=?,password_encrypted=?,initial_password=0 WHERE username=? AND password_hash=?",
                Passwords.hash(new), vault.encrypt(new), user, record["password_hash"])
            if (updated != 1) throw ApiError(409, "密码已被修改，请重新登录")
            jdbc.update("DELETE FROM sessions WHERE username=?", user)
            audit.record(user, "修改密码", "account", user, user)
        }
    }
}
