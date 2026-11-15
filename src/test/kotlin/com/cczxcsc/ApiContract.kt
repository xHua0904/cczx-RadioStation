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
    private val client = HttpClient.newHttpClient()
    private val song = mapOf("author" to "测试同学", "class_name" to "高一一班", "period" to Weeks.current().key, "content" to "记录校园生活，分享身边的温暖。")

    @BeforeEach fun clear() { repo.jdbc.update("DELETE FROM submissions"); repo.jdbc.update("DELETE FROM counters"); repo.jdbc.update("DELETE FROM sessions"); repo.jdbc.update("DELETE FROM announcements") }
    private fun request(method: String, path: String, body: Any? = null, token: String? = null): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray())
    }
    private fun data(response: HttpResponse<ByteArray>): JsonNode = json.readTree(response.body())
    private fun login(): String {
        val response = request("POST", "/api/admin/login", mapOf("password" to "TestAdmin123"))
        assertEquals(200, response.statusCode())
        return data(response).path("token").asText()
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
        assertEquals("高一一班", item.path("class_name").asText())
        val id = item.path("id").asLong()
        assertEquals(400, request("PATCH", "/api/admin/submissions/$id", mapOf("broadcast_date" to "2026-02-30"), token).statusCode())
        assertEquals(200, request("PATCH", "/api/admin/submissions/$id", mapOf("status" to "已通过", "score" to 88, "broadcast_date" to "2026-12-01", "note" to "已安排"), token).statusCode())
        val approved = data(request("POST", "/api/submissions/track", mapOf("code" to code, "author" to "测试同学")))
        assertEquals("已通过", approved.path("status").asText()); assertEquals("已安排", approved.path("note").asText())
        val stats = data(request("GET", "/api/admin/stats", token = token))
        assertEquals(1, stats.path("adopted").asInt()); assertEquals(8, stats.path("weekly").size())
        val csv = request("GET", "/api/admin/export?format=csv", token = token)
        assertEquals(200, csv.statusCode()); assertTrue(csv.body().toString(Charsets.UTF_8).contains(song["content"]!!))
        val excel = request("GET", "/api/admin/export?format=xlsx", token = token)
        assertEquals(200, excel.statusCode())
        XSSFWorkbook(ByteArrayInputStream(excel.body())).use { workbook ->
            assertEquals(2, workbook.numberOfSheets); assertEquals(song["content"], workbook.getSheet("投稿明细").getRow(1).getCell(5).stringCellValue)
            assertFalse((0 until workbook.getSheet("投稿明细").getRow(0).lastCellNum).any { workbook.getSheet("投稿明细").getRow(0).getCell(it).stringCellValue == "投稿期次" })
        }
        assertEquals(200, request("DELETE", "/api/admin/submissions/$id", token = token).statusCode())
        assertEquals(0, data(request("GET", "/api/admin/stats", token = token)).path("total").asInt())
        assertEquals(200, request("POST", "/api/admin/logout", emptyMap<String, String>(), token).statusCode())
        assertEquals(401, request("GET", "/api/admin/stats", token = token).statusCode())
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

    @Test fun `公告发布者固定且撤回后不出现在投稿页`() {
        val token = login()
        val response = request("POST", "/api/admin/announcements", mapOf("title" to "下一期投稿计划", "content" to "欢迎分享校园见闻", "kind" to "投稿计划", "publisher" to "伪造发布者"), token)
        assertEquals(200, response.statusCode())
        val id = data(response).path("id").asLong()
        assertEquals("新媒体中心", data(request("GET", "/api/announcements")).path("items").first().path("publisher").asText())
        assertEquals(200, request("PATCH", "/api/admin/announcements/$id", mapOf("published" to false), token).statusCode())
        assertEquals(0, data(request("GET", "/api/announcements")).path("items").size())
        assertEquals(1, data(request("GET", "/api/admin/announcements", token = token)).path("items").size())
    }
}
