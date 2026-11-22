package com.cczxcsc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors

abstract class ApiContract {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var repo: SubmissionRepository
    @Autowired private lateinit var auth: AuthService
    private val client = HttpClient.newHttpClient()
    private val song = mapOf("author" to "测试同学", "class_name" to "高一一班", "period" to Weeks.current().key, "content" to "记录校园生活，分享身边的温暖。")

    @BeforeEach fun clear() { repo.jdbc.update("DELETE FROM submissions"); repo.jdbc.update("DELETE FROM counters"); repo.jdbc.update("DELETE FROM sessions"); repo.jdbc.update("DELETE FROM announcements"); repo.jdbc.update("DELETE FROM audit_log"); repo.jdbc.update("DELETE FROM accounts"); auth.addAccount("测试管理员") }
    private fun request(method: String, path: String, body: Any? = null, token: String? = null): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray())
    }
    private fun data(response: HttpResponse<ByteArray>): JsonNode = json.readTree(response.body())
    private fun login(): String {
        val response = request("POST", "/api/admin/login", mapOf("username" to "测试管理员", "password" to "cczx123456"))
        assertEquals(200, response.statusCode())
        return data(response).path("token").asText()
    }

    @Test fun `操作人来自登录账号且审批排期消息删除均可追溯`() {
        auth.addAccount("网络部"); auth.addAccount("科创社")
        fun accountLogin(name: String): String {
            val response = request("POST", "/api/admin/login", mapOf("username" to name, "password" to "cczx123456"))
            assertEquals(200, response.statusCode()); assertEquals(name, data(response).path("username").asText())
            return data(response).path("token").asText()
        }
        val network = accountLogin("网络部"); val science = accountLogin("科创社")
        assertEquals("网络部", data(request("GET", "/api/admin/me", token = network)).path("username").asText())
        assertEquals(400, request("POST", "/api/admin/login", mapOf("password" to "cczx123456")).statusCode())
        assertEquals(401, request("GET", "/api/admin/audit").statusCode())
        assertEquals(404, request("POST", "/api/admin/accounts", mapOf("username" to "注册账号"), network).statusCode())
        assertEquals(200, request("POST", "/api/submissions", song).statusCode())
        val id = data(request("GET", "/api/admin/review-summary", token = network)).path("ids").first().asLong()
        assertEquals(200, request("POST", "/api/admin/submissions/approve-all", mapOf("ids" to listOf(id), "confirmed" to true, "reviewed_by" to "伪造审稿人"), network).statusCode())
        assertEquals(200, request("POST", "/api/admin/submissions/batch", mapOf("ids" to listOf(id), "action" to "schedule", "broadcast_date" to "2026-12-01", "broadcast_time" to "12:30"), science).statusCode())
        assertEquals(200, request("PATCH", "/api/admin/submissions/$id", mapOf("status" to "已播出", "updated_by" to "伪造操作人"), science).statusCode())
        val row = data(request("GET", "/api/admin/schedule", token = science)).path("items").first()
        assertEquals("网络部", row.path("reviewed_by").asText()); assertEquals("科创社", row.path("scheduled_by").asText())
        assertEquals("科创社", row.path("updated_by").asText()); assertEquals("12:30", row.path("broadcast_time").asText())
        val before = data(request("GET", "/api/admin/audit", token = science)).path("total").asLong()
        assertEquals(400, request("PATCH", "/api/admin/submissions/$id", mapOf("score" to 101), network).statusCode())
        assertEquals(before, data(request("GET", "/api/admin/audit", token = science)).path("total").asLong())
        val message = data(request("POST", "/api/admin/announcements", mapOf("title" to "网络部公告", "content" to "测试公告", "publisher" to "伪造发布者"), network)).path("id").asLong()
        assertEquals("网络部", data(request("GET", "/api/announcements")).path("items").first().path("publisher").asText())
        assertEquals(200, request("PATCH", "/api/admin/announcements/$message", mapOf("published" to false), science).statusCode())
        val item = data(request("GET", "/api/admin/announcements", token = science)).path("items").first()
        assertEquals("网络部", item.path("publisher").asText()); assertEquals("科创社", item.path("updated_by").asText())
        assertEquals(200, request("DELETE", "/api/admin/announcements/$message", token = science).statusCode())
        assertEquals(200, request("DELETE", "/api/admin/submissions/$id", token = network).statusCode())
        val history = data(request("GET", "/api/admin/audit?target_type=submission&target_id=$id", token = network))
        assertEquals(5, history.path("total").asInt())
        assertEquals("在线投稿", history.path("items").last().path("action").asText())
        assertTrue(history.path("items").last().path("username").asText().startsWith("投稿人："))
        assertEquals("删除稿件", history.path("items").first().path("action").asText())
        assertEquals("网络部", history.path("items").first().path("username").asText())
        assertEquals(3, data(request("GET", "/api/admin/audit?target_type=announcement&target_id=$message", token = science)).path("total").asInt())
    }

    @Test fun `投稿审批与导出兼容网页接口`() {
        assertEquals(200, request("GET", "/").statusCode())
        assertEquals(200, request("GET", "/admin").statusCode())
        assertEquals(401, request("GET", "/api/admin/submissions").statusCode())
        val invalid = request("POST", "/api/submissions", song + ("content" to "稿".repeat(101)))
        assertEquals(400, invalid.statusCode()); assertTrue(data(invalid).has("error"))
        val response = request("POST", "/api/submissions", song + ("status" to "已播出"))
        assertEquals(200, response.statusCode())
        val code = data(response).path("code").asText()
        val track = data(request("POST", "/api/submissions/track", mapOf("code" to code, "author" to "测试同学")))
        assertEquals("待审核", track.path("status").asText())
        assertEquals(Weeks.current().key, track.path("period").asText()); assertFalse(track.has("contact"))
        assertEquals(404, request("POST", "/api/submissions/track", mapOf("code" to code, "author" to "其他同学")).statusCode())
        val token = login()
        val server = data(request("GET", "/api/admin/server", token = token))
        assertTrue(server.path("server_ips").isArray)
        assertTrue(server.path("server_ips").size() > 0)
        assertEquals(server.path("server_ips").first().asText(), server.path("server_ip").asText())
        assertTrue(server.path("server_ips").all { Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(it.asText()) })
        val listing = data(request("GET", "/api/admin/submissions", token = token))
        assertEquals(1, listing.path("total").asInt())
        val item = listing.path("items").first()
        assertEquals("高一1班", item.path("class_name").asText())
        val id = item.path("id").asLong()
        assertEquals(400, request("PATCH", "/api/admin/submissions/$id", mapOf("broadcast_date" to "2026-02-30"), token).statusCode())
        assertEquals(200, request("PATCH", "/api/admin/submissions/$id", mapOf("status" to "已通过", "broadcast_date" to "2026-12-01", "note" to "已安排"), token).statusCode())
        val approved = data(request("POST", "/api/submissions/track", mapOf("code" to code, "author" to "测试同学")))
        assertEquals("已通过", approved.path("status").asText()); assertEquals("已安排", approved.path("note").asText())
        val stats = data(request("GET", "/api/admin/stats", token = token))
        assertEquals(1, stats.path("adopted").asInt()); assertEquals(8, stats.path("weekly").size())
        val csv = request("GET", "/api/admin/export?format=csv", token = token)
        assertEquals(200, csv.statusCode()); assertTrue(csv.body().toString(Charsets.UTF_8).contains(song["content"]!!))
        val excel = request("GET", "/api/admin/export?format=xlsx", token = token)
        assertEquals(200, excel.statusCode())
        XSSFWorkbook(ByteArrayInputStream(excel.body())).use { workbook ->
            assertEquals(2, workbook.numberOfSheets); assertEquals(song["content"], workbook.getSheet("投稿明细").getRow(1).getCell(9).stringCellValue)
            assertFalse((0 until workbook.getSheet("投稿明细").getRow(0).lastCellNum).any { workbook.getSheet("投稿明细").getRow(0).getCell(it).stringCellValue in setOf("投稿期次", "评分") })
        }
        assertEquals(200, request("DELETE", "/api/admin/submissions/$id", token = token).statusCode())
        assertEquals(0, data(request("GET", "/api/admin/stats", token = token)).path("total").asInt())
        assertEquals(200, request("POST", "/api/admin/logout", emptyMap<String, String>(), token).statusCode())
        assertEquals(401, request("GET", "/api/admin/stats", token = token).statusCode())
    }

    @Test fun `结构化点播分别保存选填内容并用于搜索日程和导出`() {
        val payload = mapOf("author" to "测试同学", "class_name" to "高一11班", "song_name" to "稻香", "artist" to "周杰伦",
            "message" to "愿同学们找到自己的节奏。", "reason" to "想在午间广播听到这首歌。", "contact" to "不公开的联系方式")
        val response = request("POST", "/api/submissions", payload)
        assertEquals(200, response.statusCode())
        val code = data(response).path("code").asText()
        val stored = repo.byCode(code)!!
        assertEquals("稻香", stored.title); assertEquals("点歌祝福", stored.category)
        assertEquals(payload["message"], stored.message); assertEquals(payload["reason"], stored.reason); assertEquals("", stored.legacyContent)
        val raw = repo.jdbc.queryForObject("SELECT content FROM submissions WHERE id=?", String::class.java, stored.id)!!
        assertEquals(payload["reason"], SongRequestContent.decode(raw)?.reason)
        val token = login()
        val search = data(request("GET", "/api/admin/submissions?q=" + java.net.URLEncoder.encode("周杰伦", Charsets.UTF_8), token = token))
        assertEquals(1, search.path("total").asInt()); assertEquals("周杰伦", search.path("items").first().path("artist").asText())
        assertEquals(200, request("PATCH", "/api/admin/submissions/${stored.id}", mapOf("status" to "已通过", "broadcast_date" to "2026-12-01"), token).statusCode())
        val scheduled = data(request("GET", "/api/admin/schedule", token = token)).path("items").first()
        assertEquals(payload["message"], scheduled.path("message").asText()); assertEquals(payload["reason"], scheduled.path("reason").asText())
        val track = data(request("POST", "/api/submissions/track", mapOf("code" to code, "author" to "测试同学")))
        assertEquals("稻香", track.path("song_name").asText()); assertEquals("周杰伦", track.path("artist").asText())
        listOf("message", "reason", "content", "contact").forEach { assertFalse(track.has(it)) }
        val csv = request("GET", "/api/admin/export?format=csv", token = token).body().toString(Charsets.UTF_8)
        listOf("歌曲名称", "歌手", "简短寄语", "点播理由", payload["message"]!!, payload["reason"]!!).forEach { assertTrue(csv.contains(it)) }
        XSSFWorkbook(ByteArrayInputStream(request("GET", "/api/admin/export?format=xlsx", token = token).body())).use { workbook ->
            val sheet = workbook.getSheet("投稿明细")
            assertEquals(19, sheet.getRow(0).lastCellNum.toInt())
            listOf("稻香", "周杰伦", payload["message"], payload["reason"]).forEachIndexed { index, expected -> assertEquals(expected, sheet.getRow(1).getCell(index + 4).stringCellValue) }
            assertEquals("", sheet.getRow(1).getCell(9).stringCellValue)
        }
    }

    @Test fun `点播必须有歌名歌手且两项选填内容分别限制长度`() {
        val required = mapOf("author" to "测试同学", "class_name" to "高一1班", "song_name" to "稻香", "artist" to "周杰伦")
        for ((field, value) in listOf("song_name" to " ", "artist" to " ", "song_name" to "歌".repeat(81), "artist" to "人".repeat(81), "message" to "语".repeat(101), "reason" to "理".repeat(301))) {
            assertEquals(400, request("POST", "/api/submissions", required + (field to value)).statusCode(), field)
        }
        assertEquals(0L, repo.count())
        val empty = request("POST", "/api/submissions", required)
        assertEquals(200, empty.statusCode())
        val stored = repo.byCode(data(empty).path("code").asText())!!
        assertEquals("", stored.message); assertEquals("", stored.reason); assertEquals("", stored.legacyContent)
        assertEquals(200, request("POST", "/api/submissions", required + mapOf("message" to "语".repeat(100), "reason" to "理".repeat(300))).statusCode())
    }

    @Test fun `历史正文保持原文且普通JSON不能误作结构化点播`() {
        val original = """{"message":"旧寄语","reason":"旧理由"}"""
        val response = request("POST", "/api/submissions", song + ("content" to original))
        val stored = repo.byCode(data(response).path("code").asText())!!
        assertEquals(original, stored.content); assertEquals(original, stored.legacyContent)
        assertEquals("", stored.message); assertEquals("", stored.reason)
        repo.jdbc.update("UPDATE submissions SET song_name=?,artist=? WHERE id=?", "历史歌曲", "历史歌手", stored.id)
        assertEquals(original, repo.byId(stored.id)!!.legacyContent)
        assertNull(SongRequestContent.decode(original))
        assertNull(SongRequestContent.decode("""{"_format":"cczx-song-request-v1","message":0,"reason":""}"""))
    }

    @Test fun `并发投稿编号不会重复且不会丢失`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..16).map { executor.submit<String> {
                val response = request("POST", "/api/submissions", song)
                assertEquals(200, response.statusCode())
                data(response).path("code").asText()
            } }
            assertEquals(16, futures.map { it.get() }.toSet().size)
            assertEquals(16L, repo.count())
        } finally { executor.shutdownNow() }
    }

    @Test fun `原有通用稿件接口继续可用`() {
        val response = request("POST", "/api/submissions", mapOf("author" to "测试同学", "class_name" to "高一一班", "content" to "这是一篇需要保留兼容的旧格式校园稿件。"))
        assertEquals(200, response.statusCode())
        val code = data(response).path("code").asText()
        val result = data(request("GET", "/api/submissions/track?code=$code&author=${java.net.URLEncoder.encode("测试同学", Charsets.UTF_8)}"))
        assertEquals("其他", result.path("category").asText())
        assertEquals(Weeks.current().key, result.path("period").asText())
    }

    @Test fun `全部审批需要确认且只处理确认前的快照`() {
        repeat(3) { assertEquals(200, request("POST", "/api/submissions", song).statusCode()) }
        val token = login()
        val summary = data(request("GET", "/api/admin/review-summary", token = token))
        val ids = summary.path("ids").map { it.asLong() }
        assertEquals(400, request("POST", "/api/admin/submissions/approve-all", mapOf("ids" to ids), token).statusCode())
        assertEquals(200, request("POST", "/api/submissions", song).statusCode())
        val approved = data(request("POST", "/api/admin/submissions/approve-all", mapOf("ids" to ids, "confirmed" to true), token))
        assertEquals(3, approved.path("updated").asInt()); assertEquals(1, approved.path("remaining").asInt())
        assertEquals(3, data(request("GET", "/api/admin/submissions?phase=reviewed", token = token)).path("total").asInt())
        assertEquals(1, data(request("GET", "/api/admin/submissions?phase=pending", token = token)).path("total").asInt())
        assertEquals(409, request("POST", "/api/admin/submissions/approve-all", mapOf("ids" to ids, "confirmed" to true), token).statusCode())
        assertEquals(200, request("POST", "/api/admin/submissions/batch", mapOf("ids" to ids, "action" to "schedule", "broadcast_date" to "2026-12-01", "broadcast_time" to "12:30"), token).statusCode())
        assertEquals(3, data(request("GET", "/api/admin/schedule", token = token)).path("items").size())
    }

    @Test fun `已审批和已安排列表分别分页且日历状态切换保留日程`() {
        repeat(18) { index -> assertEquals(200, request("POST", "/api/submissions", song + ("content" to "列表分离稿件第${index + 1}篇")).statusCode()) }
        val token = login()
        val ids = data(request("GET", "/api/admin/review-summary", token = token)).path("ids").map { it.asLong() }
        assertEquals(200, request("POST", "/api/admin/submissions/approve-all", mapOf("ids" to ids, "confirmed" to true), token).statusCode())
        assertEquals(200, request("POST", "/api/admin/submissions", song + ("status" to "已退稿"), token).statusCode())
        assertEquals(200, request("POST", "/api/submissions", song).statusCode())
        assertEquals(200, request("POST", "/api/admin/submissions/batch", mapOf("ids" to ids.take(16), "action" to "schedule", "broadcast_date" to "2026-12-01", "broadcast_time" to "12:30"), token).statusCode())
        val scheduledPath = "/api/admin/submissions?phase=reviewed&schedule=scheduled"
        val unscheduledPath = "/api/admin/submissions?phase=reviewed&schedule=unscheduled"
        val unscheduled = data(request("GET", unscheduledPath, token = token))
        assertEquals(3, unscheduled.path("total").asInt())
        assertTrue(unscheduled.path("items").all { it.path("broadcast_date").asText().isEmpty() && it.path("status").asText() != "待审核" })
        val scheduled = data(request("GET", scheduledPath, token = token))
        assertEquals(16, scheduled.path("total").asInt()); assertEquals(2, scheduled.path("pages").asInt())
        assertEquals(15, scheduled.path("items").size())
        assertEquals(1, data(request("GET", "$scheduledPath&page=2", token = token)).path("items").size())
        assertTrue(scheduled.path("items").all { it.path("broadcast_date").asText() == "2026-12-01" })
        val id = ids.first()
        assertEquals(200, request("PATCH", "/api/admin/submissions/$id", mapOf("note" to "保留播出备注"), token).statusCode())
        for (status in listOf("已播出", "已通过")) {
            assertEquals(200, request("PATCH", "/api/admin/submissions/$id", mapOf("status" to status), token).statusCode())
            val row = data(request("GET", "/api/admin/schedule", token = token)).path("items").first { it.path("id").asLong() == id }
            assertEquals(status, row.path("status").asText())
            assertEquals("2026-12-01", row.path("broadcast_date").asText()); assertEquals("12:30", row.path("broadcast_time").asText())
            assertEquals("保留播出备注", row.path("note").asText())
            assertEquals(if (status == "已播出") 1 else 0, data(request("GET", "$scheduledPath&status=已播出", token = token)).path("total").asInt())
            assertEquals(16, data(request("GET", scheduledPath, token = token)).path("total").asInt())
        }
        val code = repo.byId(id)!!.code
        assertEquals(1, data(request("GET", "$scheduledPath&q=$code", token = token)).path("total").asInt())
        assertEquals(0, data(request("GET", "$unscheduledPath&q=$code", token = token)).path("total").asInt())
        assertEquals(200, request("POST", "/api/admin/submissions/batch", mapOf("ids" to listOf(id), "action" to "clear_schedule"), token).statusCode())
        assertEquals(15, data(request("GET", scheduledPath, token = token)).path("total").asInt())
        assertEquals(4, data(request("GET", unscheduledPath, token = token)).path("total").asInt())
    }

    @Test fun `公告发布者使用登录账号且撤回后不出现在投稿页`() {
        val token = login()
        val response = request("POST", "/api/admin/announcements", mapOf("title" to "下一期投稿计划", "content" to "欢迎分享校园见闻", "kind" to "投稿计划", "publisher" to "伪造发布者"), token)
        assertEquals(200, response.statusCode())
        val id = data(response).path("id").asLong()
        assertEquals("公告", repo.jdbc.queryForObject("SELECT kind FROM announcements WHERE id=?", String::class.java, id))
        assertEquals("", repo.jdbc.queryForObject("SELECT period FROM announcements WHERE id=?", String::class.java, id))
        val announcement = data(request("GET", "/api/announcements")).path("items").first()
        assertFalse(announcement.has("kind")); assertFalse(announcement.has("period"))
        assertEquals("测试管理员", data(request("GET", "/api/announcements")).path("items").first().path("publisher").asText())
        assertEquals(200, request("PATCH", "/api/admin/announcements/$id", mapOf("published" to false), token).statusCode())
        assertEquals(0, data(request("GET", "/api/announcements")).path("items").size())
        assertEquals(1, data(request("GET", "/api/admin/announcements", token = token)).path("items").size())
    }
    @Test fun `公开投稿只接受指定班级并兼容旧中文班级写法`() {
        val choices = data(request("GET", "/api/meta")).path("classes")
        assertEquals(20, choices.size())
        assertTrue(choices.any { it.asText() == "高一11班" }); assertTrue(choices.any { it.asText() == "高二9班" })
        for (name in listOf("高一1班", "高一11班", "高二1班", "高二九班")) {
            val result = request("POST", "/api/submissions", song + ("class_name" to name))
            assertEquals(200, result.statusCode())
        }
        for (name in listOf("高一0班", "高一12班", "高二10班", "高三1班", "任意班级")) assertEquals(400, request("POST", "/api/submissions", song + ("class_name" to name)).statusCode())
        assertEquals(4L, repo.count())
        assertEquals("高二9班", repo.jdbc.queryForObject("SELECT class_name FROM submissions ORDER BY id DESC LIMIT 1", String::class.java))
    }
    @Test fun `评分接口和导出取消但历史评分不被清空且备注仍可保存`() {
        val created = data(request("POST", "/api/submissions", song))
        val code = created.path("code").asText()
        val id = repo.byCode(code)!!.id
        repo.jdbc.update("UPDATE submissions SET score=88 WHERE id=?", id)
        val token = login()
        assertFalse(data(request("GET", "/api/admin/submissions", token = token)).path("items").first().has("score"))
        assertFalse(data(request("GET", "/api/submissions/track?code=$code&author=%E6%B5%8B%E8%AF%95%E5%90%8C%E5%AD%A6")).has("score"))
        assertEquals(400, request("PATCH", "/api/admin/submissions/$id", mapOf("score" to 99, "note" to "不应写入"), token).statusCode())
        assertEquals(88, repo.jdbc.queryForObject("SELECT score FROM submissions WHERE id=?", Int::class.java, id))
        assertEquals(200, request("PATCH", "/api/admin/submissions/$id", mapOf("note" to "保留备注能力"), token).statusCode())
        assertEquals("保留备注能力", repo.byId(id)!!.note)
        val csv = request("GET", "/api/admin/export?format=csv", token = token).body().toString(Charsets.UTF_8)
        assertFalse(csv.lineSequence().first().contains("评分"))
        assertEquals("修改备注", data(request("GET", "/api/admin/audit?target_type=submission&target_id=$id", token = token)).path("items").first().path("action").asText())
    }
    @Test fun `页面不缓存且构建后的脚本和样式均携带资源版本`() {
        for (path in listOf("/", "/admin", "/admin.html", "/credits.html")) {
            val response = request("GET", path)
            assertEquals(200, response.statusCode())
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
            val html = response.body().toString(Charsets.UTF_8)
            assertFalse(html.contains("@assets.version@"))
            val assets = Regex("(?:src|href)=\"((?:js|css)/[^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
            assertTrue(assets.isNotEmpty())
            assertTrue(assets.all { Regex(".+\\?v=\\d{14}").matches(it) })
            for (asset in assets) assertEquals(200, request("GET", "/$asset").statusCode())
        }
    }
}
