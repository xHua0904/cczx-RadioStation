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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["app.submissions-per-minute=3", "app.trusted-proxies=127.0.0.1/32"])
class ApiPolicyTest {
    companion object {
        private val directory = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        private val sources = AtomicInteger()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${directory.resolve("test.db")}" }
            registry.add("app.data-dir") { directory.toString() }
        }
    }
    @LocalServerPort private var port = 0
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var auth: AuthService
    @Autowired private lateinit var repo: SubmissionRepository
    private val client = HttpClient.newHttpClient()
    private var source = ""
    private val song = mapOf("author" to "测试同学", "class_name" to "高一一班", "content" to "投稿限流测试。")
    @BeforeEach fun clear() {
        for (table in listOf("sessions", "submissions", "announcements", "audit_log", "accounts")) repo.jdbc.update("DELETE FROM $table")
        auth.addAccount("网络部"); auth.addAccount("科创社")
        source = "192.0.2.${sources.incrementAndGet()}"
    }
    private fun request(method: String, path: String, body: Any? = null, token: String? = null, ip: String = source): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("Content-Type", "application/json").header("X-Forwarded-For", ip)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun login(name: String): String {
        val response = request("POST", "/api/admin/login", mapOf("username" to name, "password" to "cczx123456"))
        assertEquals(200, response.statusCode(), response.body())
        val data = json.readTree(response.body()); assertEquals("admin", data.path("role").asText())
        return data.path("token").asText()
    }
    @Test fun `超过投稿额度返回429并隔离来源且后台仍可登记`() {
        repeat(3) { assertEquals(200, request("POST", "/api/submissions", song).statusCode()) }
        val rejected = request("POST", "/api/submissions", song)
        assertEquals(429, rejected.statusCode()); assertTrue(rejected.headers().firstValue("Retry-After").get().toInt() in 1..60)
        assertEquals(3L, repo.count())
        assertEquals(200, request("POST", "/api/submissions", song, ip = "198.51.100.90").statusCode())
        assertEquals(200, request("POST", "/api/admin/submissions", song, login("网络部")).statusCode())
    }
    @Test fun `所有账号和旧编辑字段均具有全部管理权限`() {
        if (repo.jdbc.queryForList("PRAGMA table_info(accounts)").none { it["name"] == "role" }) repo.jdbc.execute("ALTER TABLE accounts ADD COLUMN role TEXT NOT NULL DEFAULT 'editor'")
        repo.jdbc.update("UPDATE accounts SET role='editor'")
        val tokens = listOf(login("网络部"), login("科创社"))
        for (token in tokens) {
            for (path in listOf("/api/admin/server", "/api/admin/audit", "/api/admin/logs?lines=100", "/api/admin/export?format=csv", "/api/admin/export?format=xlsx")) assertEquals(200, request("GET", path, token = token).statusCode())
            assertEquals("admin", json.readTree(request("GET", "/api/admin/me", token = token).body()).path("role").asText())
        }
        val item = request("POST", "/api/admin/submissions", song, tokens[0]); assertEquals(200, item.statusCode())
        val id = repo.jdbc.queryForObject("SELECT id FROM submissions LIMIT 1", Long::class.java)!!
        assertEquals(200, request("PATCH", "/api/admin/submissions/$id", mapOf("status" to "已通过"), tokens[1]).statusCode())
        assertEquals(200, request("DELETE", "/api/admin/submissions/$id", token = tokens[1]).statusCode())
    }
    @Test fun `公告可选择公开署名且实际操作人不能伪造`() {
        val token = login("网络部")
        for ((mode, publisher) in listOf("current_account" to "网络部", "media_center" to "新媒体中心")) {
            val response = request("POST", "/api/admin/announcements", mapOf("title" to mode, "content" to "公告正文", "publish_as" to mode, "publisher" to "伪造署名"), token)
            assertEquals(200, response.statusCode())
            val id = json.readTree(response.body()).path("id").asLong()
            val item = json.readTree(request("GET", "/api/announcements").body()).path("items").first()
            assertEquals(publisher, item.path("publisher").asText()); assertEquals("网络部", item.path("updated_by").asText())
            assertEquals("网络部", repo.jdbc.queryForObject("SELECT username FROM audit_log WHERE target_id=? AND action='发布公告'", String::class.java, id.toString()))
        }
        assertEquals(0, repo.jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE username='新媒体中心'", Int::class.java))
        assertEquals(400, request("POST", "/api/admin/announcements", mapOf("title" to "错误身份", "content" to "正文", "publish_as" to "arbitrary"), token).statusCode())
    }
    @Test fun `删除账号会注销该账号会话并保留其他账号和历史公告`() {
        val first = login("网络部"); val second = login("科创社")
        assertEquals(200, request("POST", "/api/admin/announcements", mapOf("title" to "保留历史", "content" to "正文"), first).statusCode())
        auth.deleteAccount("网络部")
        assertEquals(401, request("GET", "/api/admin/me", token = first).statusCode())
        assertEquals(200, request("GET", "/api/admin/me", token = second).statusCode())
        assertEquals("网络部", json.readTree(request("GET", "/api/announcements").body()).path("items").first().path("publisher").asText())
    }
    @Test fun `账号导出及日期筛选日志不泄露凭据且开发者日志需要登录`() {
        val token = login("网络部")
        assertEquals(401, request("GET", "/api/admin/logs").statusCode())
        assertEquals(400, request("GET", "/api/admin/logs?lines=999", token = token).statusCode())
        assertEquals(200, request("GET", "/api/admin/logs?lines=100", token = token).statusCode())
        assertEquals(200, request("GET", "/api/admin/export?format=csv", token = token).statusCode())
        val exports = json.readTree(request("GET", "/api/admin/audit?target_type=export&username=%E7%BD%91%E7%BB%9C%E9%83%A8", token = token).body())
        assertEquals(1, exports.path("total").asInt())
        assertEquals("网络部", exports.path("items").first().path("username").asText())
        val today = java.time.LocalDate.now(schoolZone).toString()
        val accountLogs = request("GET", "/api/admin/audit?target_type=account&start_date=$today&end_date=$today", token = token)
        assertEquals(200, accountLogs.statusCode())
        assertTrue(json.readTree(accountLogs.body()).path("items").any { it.path("action").asText() == "登录后台" })
        assertFalse(accountLogs.body().contains(token)); assertFalse(accountLogs.body().contains(Passwords.DEFAULT_ADMIN_PASSWORD))
        assertEquals(400, request("GET", "/api/admin/audit?start_date=2026-12-31&end_date=2026-01-01", token = token).statusCode())
        assertEquals(400, request("GET", "/api/admin/audit?start_date=bad-date", token = token).statusCode())
        assertEquals(200, request("POST", "/api/admin/logout", emptyMap<String, String>(), token).statusCode())
        assertEquals(1, repo.jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE username=? AND action='退出后台'", Int::class.java, "网络部"))
    }
}
