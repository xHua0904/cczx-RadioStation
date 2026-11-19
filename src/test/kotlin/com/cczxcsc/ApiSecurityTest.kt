package com.cczxcsc

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["app.admin-password=cczx123456", "app.trusted-proxies=127.0.0.1/32"])
class ApiSecurityTest {
    companion object {
        private val directory = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        private val sources = AtomicInteger()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${directory.resolve("test.db")}" }
            registry.add("app.data-dir") { directory.resolve("data").toString() }
            registry.add("app.secrets-dir") { directory.resolve("secrets").toString() }
        }
    }
    @LocalServerPort private var port = 0
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var auth: AuthService
    @Autowired private lateinit var repo: SubmissionRepository
    private val client = HttpClient.newHttpClient()
    private var ip = ""
    private val strong = "SecurityTest123"
    private val song = mapOf("author" to "隐私测试", "class_name" to "高一一班", "content" to "安全回归测试稿件。", "contact" to "13800138000")

    @BeforeEach fun clear() {
        repo.jdbc.update("DELETE FROM sessions"); repo.jdbc.update("DELETE FROM submissions")
        repo.jdbc.update("DELETE FROM accounts WHERE username<>?", Passwords.DEFAULT_ACCOUNT)
        repo.jdbc.update("DELETE FROM announcements"); repo.jdbc.update("DELETE FROM audit_log")
        auth.resetPassword(); ip = "192.0.2.${sources.incrementAndGet()}"
    }
    private fun request(method: String, path: String, body: Any? = null, token: String? = null, source: String = ip, raw: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("Content-Type", "application/json").header("X-Forwarded-For", source)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val content = raw ?: body?.let(json::writeValueAsString)
        return client.send(builder.method(method, content?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun login(name: String = Passwords.DEFAULT_ACCOUNT, password: String = Passwords.DEFAULT_ADMIN_PASSWORD): String {
        val response = request("POST", "/api/admin/login", mapOf("username" to name, "password" to password))
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body()).path("token").asText()
    }
    private fun admin(): String { auth.changePassword(Passwords.DEFAULT_ADMIN_PASSWORD, strong); return login(password = strong) }

    @Test fun `初始和重置密码会话只能改密查询本人或退出`() {
        val token = login()
        assertTrue(json.readTree(request("GET", "/api/admin/me", token = token).body()).path("initial_password").asBoolean())
        for ((method, path) in listOf("GET" to "/api/admin/submissions", "GET" to "/api/admin/export", "GET" to "/api/admin/audit", "POST" to "/api/admin/submissions/approve-all", "DELETE" to "/api/admin/submissions/1")) {
            val response = request(method, path, if (method == "POST") mapOf("confirmed" to true) else null, token)
            assertEquals(403, response.statusCode()); assertEquals("PASSWORD_CHANGE_REQUIRED", json.readTree(response.body()).path("code").asText())
        }
        assertEquals(400, request("POST", "/api/admin/password", mapOf("old_password" to "cczx123456", "new_password" to "1234567890"), token).statusCode())
        assertEquals(200, request("POST", "/api/admin/password", mapOf("old_password" to "cczx123456", "new_password" to strong), token).statusCode())
        assertEquals(401, request("GET", "/api/admin/me", token = token).statusCode())
        val full = login(password = strong)
        assertEquals(200, request("GET", "/api/admin/submissions", token = full).statusCode())
        assertEquals(400, request("POST", "/api/admin/password", mapOf("old_password" to strong, "new_password" to "cczx123456"), full).statusCode())
        auth.resetPassword()
        assertEquals(401, request("GET", "/api/admin/me", token = full).statusCode())
        assertEquals(403, request("GET", "/api/admin/submissions", token = login()).statusCode())
    }

    @Test fun `所有账号包括旧编辑账号具有相同的最高管理权限`() {
        val admin = admin()
        auth.addAccount("网络部"); auth.changePassword("cczx123456", strong, "网络部")
        auth.addAccount("科创社"); auth.changePassword("cczx123456", strong, "科创社")
        assertEquals("admin", auth.accountRole("网络部"))
        assertEquals("admin", repo.jdbc.queryForObject("SELECT role FROM accounts WHERE username='网络部'", String::class.java))
        val network = login("网络部", strong)
        val science = login("科创社", strong)
        // 模拟已有库仍保存 editor；旧会话与新登录都应得到完整权限。
        repo.jdbc.update("UPDATE accounts SET role='editor' WHERE username='网络部'")
        val legacyLogin = login("网络部", strong)
        for (token in listOf(admin, network, science, legacyLogin)) {
            assertEquals("admin", json.readTree(request("GET", "/api/admin/me", token = token).body()).path("role").asText())
        }
        assertEquals(200, request("POST", "/api/submissions", song).statusCode())
        val id = repo.jdbc.queryForObject("SELECT id FROM submissions LIMIT 1", Long::class.java)!!
        for (token in listOf(admin, network, science, legacyLogin)) {
            for (path in listOf("/api/admin/export?format=csv", "/api/admin/export?format=xlsx", "/api/admin/server")) assertEquals(200, request("GET", path, token = token).statusCode())
            assertEquals("13800138000", json.readTree(request("GET", "/api/admin/submissions", token = token).body()).path("items").first().path("contact").asText())
        }
        assertEquals(200, request("PATCH", "/api/admin/submissions/$id", mapOf("status" to "已通过", "broadcast_date" to "2026-12-01"), network).statusCode())
        assertEquals("13800138000", json.readTree(request("GET", "/api/admin/schedule", token = science).body()).path("items").first().path("contact").asText())
        val passwordBefore = repo.jdbc.queryForObject("SELECT password_hash FROM accounts WHERE username='网络部'", String::class.java)
        auth.initialize()
        assertEquals(0, repo.jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE role<>'admin'", Int::class.java))
        assertEquals(passwordBefore, repo.jdbc.queryForObject("SELECT password_hash FROM accounts WHERE username='网络部'", String::class.java))
        assertEquals(200, request("GET", "/api/admin/me", token = network).statusCode())
        assertThrows(ApiError::class.java) { auth.setRole("网络部", "editor") }
        assertThrows(ApiError::class.java) { auth.addAccount("低权限账号", "editor") }
        val announcement = request("POST", "/api/admin/announcements", mapOf("title" to "安全测试公告", "content" to "管理人员权限相同"), science)
        assertEquals(200, announcement.statusCode())
        val message = json.readTree(announcement.body()).path("id").asLong()
        assertEquals(200, request("DELETE", "/api/admin/announcements/$message", token = network).statusCode())
        assertEquals(200, request("DELETE", "/api/admin/submissions/$id", token = science).statusCode())
        auth.setRole("网络部", "admin")
        assertEquals(401, request("GET", "/api/admin/me", token = network).statusCode())
        assertEquals(200, request("GET", "/api/admin/server", token = login("网络部", strong)).statusCode())
    }

    @Test fun `投稿限流隔离来源且路径编码不能绕过请求体上限`() {
        repeat(20) { assertEquals(200, request("POST", "/api/submissions", song).statusCode()) }
        val rejected = request("POST", "/api/submissions", song)
        assertEquals(429, rejected.statusCode()); assertEquals("60", rejected.headers().firstValue("Retry-After").orElse(""))
        assertEquals(200, request("POST", "/api/submissions", song, source = "198.51.100.90").statusCode())
        for (path in listOf("/api/admin/log%69n", "/api/submissions;extra", "/api/%73ubmissions")) {
            assertTrue(request("POST", path, song).statusCode() in setOf(400, 404), path)
        }
        assertEquals(413, request("POST", "/api/admin/login", raw = " ".repeat(4097)).statusCode())
        assertEquals(413, request("POST", "/api/submissions", source = "198.51.100.91", raw = " ".repeat(16385)).statusCode())
    }

    @Test fun `错误登录仅限流当前来源且记录失败审计`() {
        repeat(12) { assertEquals(401, request("POST", "/api/admin/login", mapOf("username" to "新媒体中心", "password" to "错误口令")).statusCode()) }
        assertEquals(429, request("POST", "/api/admin/login", mapOf("username" to "新媒体中心", "password" to "cczx123456")).statusCode())
        assertEquals(200, request("POST", "/api/admin/login", mapOf("username" to "新媒体中心", "password" to "cczx123456"), source = "198.51.100.92").statusCode())
        assertEquals(12, repo.jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='登录失败'", Int::class.java))
    }

    @Test fun `会话空闲与绝对超时生效且公开查询不返回审核信息`() {
        val token = admin(); val hash = Passwords.digest(token)
        repo.jdbc.update("UPDATE sessions SET last_seen_at=? WHERE token=?", OffsetDateTime.now(schoolZone).minusMinutes(31).toString(), hash)
        assertEquals(401, request("GET", "/api/admin/me", token = token).statusCode())
        val second = login(password = strong)
        repo.jdbc.update("UPDATE sessions SET expires_at=? WHERE token=?", OffsetDateTime.now(schoolZone).minusSeconds(1).toString(), Passwords.digest(second))
        assertEquals(401, request("GET", "/api/admin/me", token = second).statusCode())
        assertEquals(410, request("POST", "/api/submissions/track", mapOf("code" to "GB20261119001", "author" to "隐私测试")).statusCode())
        val health = request("GET", "/api/health")
        assertEquals(200, health.statusCode()); assertEquals("no-store", health.headers().firstValue("Cache-Control").orElse(""))
        assertEquals("DENY", health.headers().firstValue("X-Frame-Options").orElse(""))
        assertTrue(health.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"))
    }
}
