package com.cczxcsc

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory

data class AdminSession(val tokenHash: String, val username: String, val role: String, val passwordChangeRequired: Boolean)

@Service
class AuthService(
    private val jdbc: JdbcTemplate, private val transactions: TransactionTemplate,
    @Value("\${app.admin-password:cczx123456}") private val initialPassword: String,
    private val vault: PasswordVault,
    private val audit: AuditService = AuditService(jdbc),
    @Value("\${app.session-max-age-seconds:7200}") private val sessionMaxAge: Long = 7200,
    @Value("\${app.session-idle-seconds:1800}") private val sessionIdle: Long = 1800
) {
    private val limiter = WindowLimiter()
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val dummyHash by lazy { Passwords.hash(Passwords.token()) }
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

    fun initialize() {
        vault.prepare()
        transactions.executeWithoutResult {
            if (account(Passwords.DEFAULT_ACCOUNT) == null) {
                val configured = initialPassword.ifBlank { Passwords.DEFAULT_ADMIN_PASSWORD }
                require(Passwords.validAdminPassword(configured)) { "首次初始化密码至少 10 位，必须包含字母和数字" }
                val legacyHash = setting("admin_hash")
                val legacyDefault = legacyHash != null && Passwords.verify("admin", legacyHash)
                val hash = if (legacyHash != null && !legacyDefault) legacyHash else Passwords.hash(configured)
                val encrypted = if (legacyHash != null && !legacyDefault) setting("admin_password_encrypted").orEmpty() else vault.encrypt(configured)
                jdbc.update("INSERT INTO accounts(username,password_hash,password_encrypted,initial_password,created_at,role) VALUES(?,?,?,?,?,'admin') ON CONFLICT(username) DO NOTHING",
                    Passwords.DEFAULT_ACCOUNT, hash, encrypted, if ((legacyHash == null || legacyDefault) && configured == Passwords.DEFAULT_ADMIN_PASSWORD) 1 else 0, nowIso())
            }
            // 保留 role 列兼容已有数据库；所有后台账号统一为管理员，不修改密码或会话。
            jdbc.update("UPDATE accounts SET role='admin' WHERE role<>'admin'")
            // 旧共用密码迁移为默认账号，历史会话已由表结构升级注销。
            jdbc.update("DELETE FROM settings WHERE key IN ('admin_hash','admin_password_encrypted','admin_password_initial')")
        }
    }

    // 仅由本机管理命令调用，网页和 HTTP API 不提供创建账号的入口。
    fun addAccount(username: String, role: String = "admin") {
        val user = name(username)
        if (role != "admin") throw ApiError(400, "所有后台账号统一为管理员，仅支持 admin 角色")
        transactions.executeWithoutResult {
            val inserted = jdbc.update("INSERT INTO accounts(username,password_hash,password_encrypted,initial_password,created_at,role) VALUES(?,?,?,1,?,?) ON CONFLICT(username) DO NOTHING",
                user, Passwords.hash(Passwords.DEFAULT_ADMIN_PASSWORD), vault.encrypt(Passwords.DEFAULT_ADMIN_PASSWORD), nowIso(), role)
            if (inserted != 1) throw ApiError(409, "账号已存在，已有密码保持不变")
        }
        println("已添加账号：$user；角色：$role；默认密码：${Passwords.DEFAULT_ADMIN_PASSWORD}，首次登录必须改密。")
    }

    fun listAccounts(): List<String> = jdbc.queryForList("SELECT username FROM accounts ORDER BY created_at,username", String::class.java)
    fun accountRole(username: String): String = account(name(username))?.let { "admin" } ?: throw ApiError(404, "账号不存在")
    fun setRole(username: String, role: String) {
        val user = name(username)
        if (role != "admin") throw ApiError(400, "所有后台账号统一为管理员，仅支持 admin 角色")
        transactions.executeWithoutResult {
            if (jdbc.update("UPDATE accounts SET role=? WHERE username=?", role, user) != 1) throw ApiError(404, "账号不存在")
            jdbc.update("DELETE FROM sessions WHERE username=?", user)
        }
        println("账号 $user 的角色已设置为 $role，该账号的会话已注销。")
    }

    fun resetPassword(username: String = Passwords.DEFAULT_ACCOUNT) {
        val user = name(username)
        transactions.executeWithoutResult {
            val updated = jdbc.update("UPDATE accounts SET password_hash=?,password_encrypted=?,initial_password=1 WHERE username=?",
                Passwords.hash(Passwords.DEFAULT_ADMIN_PASSWORD), vault.encrypt(Passwords.DEFAULT_ADMIN_PASSWORD), user)
            if (updated != 1) throw ApiError(404, "账号不存在")
            jdbc.update("DELETE FROM sessions WHERE username=?", user)
        }
        println("账号 $user 的密码已重置为 ${Passwords.DEFAULT_ADMIN_PASSWORD}，该账号的登录会话已注销。")
    }

    fun currentPassword(username: String = Passwords.DEFAULT_ACCOUNT): String {
        val user = account(name(username)) ?: throw ApiError(404, "账号不存在")
        val hash = user["password_hash"].toString()
        val password = runCatching { vault.decrypt(user["password_encrypted"].toString()) }.getOrNull()
        if (password != null && Passwords.verify(password, hash)) return password
        // 旧库仅有哈希时只能识别已知默认值，不覆盖用户已修改的密码。
        listOf(Passwords.DEFAULT_ADMIN_PASSWORD, initialPassword).distinct().forEach {
            if (it.isNotEmpty() && Passwords.verify(it, hash)) return it
        }
        throw IllegalStateException("当前密码无法查询：旧库仅有哈希，或密码查询密钥缺失。可使用 --reset-password --account=账号 恢复默认密码。")
    }

    fun login(password: String, ip: String, username: String = Passwords.DEFAULT_ACCOUNT): Map<String, Any> {
        val userName = name(username)
        if (password.length > 64) throw ApiError(400, "密码不能超过 64 位")
        if (!limiter.allow("global", 180, 60_000) || !limiter.allow("ip:$ip", 60, 60_000) ||
            !limiter.allow("pair:$ip:$userName", 12, 60_000) || !limiter.allow("account:$userName", 30, 60_000)) {
            log.warn("登录请求被限流，来源={}，账号={}", ip, userName)
            throw ApiError(429, "登录尝试过于频繁，请一分钟后再试")
        }
        val user = account(userName)
        if (!Passwords.verify(password, user?.get("password_hash")?.toString() ?: dummyHash)) {
            log.warn("登录失败，来源={}，账号={}", ip, userName)
            audit.record("未登录", "登录失败", "security", "login", "账号：$userName；来源：$ip")
            throw ApiError(401, "账号或密码不正确")
        }
        val needsChange = (user!!["initial_password"] as Number).toInt() == 1 || password == Passwords.DEFAULT_ADMIN_PASSWORD || !Passwords.validAdminPassword(password)
        val oldHash = user["password_hash"].toString()
        val upgradedHash = if ((oldHash.split('$').getOrNull(1)?.toIntOrNull() ?: 0) < 600_000) Passwords.hash(password) else oldHash
        val token = Passwords.token()
        val created = nowIso()
        val expires = OffsetDateTime.parse(created).plusSeconds(sessionMaxAge.coerceIn(300, 86_400)).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        transactions.executeWithoutResult {
            if (jdbc.update("UPDATE accounts SET password_hash=?,initial_password=? WHERE username=? AND password_hash=?", upgradedHash, if (needsChange) 1 else 0, userName, oldHash) != 1) throw ApiError(401, "密码已更新，请重新登录")
            jdbc.update("DELETE FROM sessions WHERE expires_at<?", created)
            val inserted = jdbc.update("INSERT INTO sessions(token,created_at,expires_at,username,last_seen_at) SELECT ?,?,?,username,? FROM accounts WHERE username=? AND password_hash=?",
                Passwords.digest(token), created, expires, created, userName, upgradedHash)
            if (inserted != 1) throw ApiError(401, "密码已更新，请重新登录")
            audit.record(userName, "登录成功", "security", "login", "来源：$ip")
        }
        return mapOf("ok" to true, "token" to token, "expires_at" to expires, "username" to userName, "role" to "admin",
            "initial_password" to needsChange)
    }

    fun session(header: String?): AdminSession {
        val token = if (header?.startsWith("Bearer ", ignoreCase = true) == true) header.substring(7).trim() else ""
        if (token.isEmpty() || token.length > 256) throw ApiError(401, "请先登录")
        val hash = Passwords.digest(token)
        val row = jdbc.queryForList("SELECT s.expires_at,s.last_seen_at,a.username,a.initial_password FROM sessions s JOIN accounts a ON a.username=s.username WHERE s.token=?", hash).firstOrNull()
            ?: throw ApiError(401, "登录已过期，请重新登录")
        val now = OffsetDateTime.now(schoolZone)
        val expiry = runCatching { OffsetDateTime.parse(row["expires_at"].toString()) }.getOrNull()
        val lastSeen = runCatching { OffsetDateTime.parse(row["last_seen_at"].toString()) }.getOrNull()
        if (expiry == null || lastSeen == null || !expiry.isAfter(now) || lastSeen.plusSeconds(sessionIdle.coerceIn(60, 86_400)).isBefore(now)) {
            jdbc.update("DELETE FROM sessions WHERE token=?", hash)
            throw ApiError(401, "登录已过期，请重新登录")
        }
        if (lastSeen.plusSeconds(60).isBefore(now)) jdbc.update("UPDATE sessions SET last_seen_at=? WHERE token=? AND last_seen_at=?", nowIso(), hash, row["last_seen_at"])
        return AdminSession(hash, row["username"].toString(), "admin", (row["initial_password"] as Number).toInt() == 1)
    }

    fun requireAdmin(header: String?): String = session(header).tokenHash

    fun username(header: String?): String = jdbc.queryForObject("SELECT username FROM sessions WHERE token=?", String::class.java, requireAdmin(header))!!
    fun logout(header: String?) { jdbc.update("DELETE FROM sessions WHERE token=?", requireAdmin(header)) }

    fun changePassword(old: String, new: String, username: String = Passwords.DEFAULT_ACCOUNT) {
        val user = name(username)
        if (!Passwords.validAdminPassword(new) || new == Passwords.DEFAULT_ADMIN_PASSWORD || new == old) throw ApiError(400, "新密码至少 10 位且包含字母和数字，不能使用默认密码或原密码")
        transactions.executeWithoutResult {
            val record = account(user) ?: throw ApiError(404, "账号不存在")
            if (!Passwords.verify(old, record["password_hash"].toString())) throw ApiError(400, "原密码不正确")
            val updated = jdbc.update("UPDATE accounts SET password_hash=?,password_encrypted=?,initial_password=0 WHERE username=? AND password_hash=?",
                Passwords.hash(new), vault.encrypt(new), user, record["password_hash"])
            if (updated != 1) throw ApiError(409, "密码已被修改，请重新登录")
            jdbc.update("DELETE FROM sessions WHERE username=?", user)
            audit.record(user, "修改密码", "security", "password", user)
        }
    }
}
