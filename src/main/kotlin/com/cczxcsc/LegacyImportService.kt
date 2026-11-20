package com.cczxcsc

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Service
class LegacyImportService(private val repo: SubmissionRepository, private val json: ObjectMapper,
    private val auth: AuthService, @Value("\${app.storage}") private val storage: String) {
    @Transactional
    fun importFile(path: Path) {
        require(repo.count() == 0L) { "仅允许导入到没有投稿数据的数据库，现有数据不会被覆盖" }
        val tree = json.readTree(Files.readString(path))
        require(tree.path("format").asText() == "radio-submissions-legacy-v1") { "旧数据导出文件格式不正确" }
        val rows = tree.path("submissions")
        require(rows.isArray) { "缺少投稿数据" }
        val jdbc = repo.jdbc
        val names = listOf("id", "code", "author", "class_name", "contact", "category", "title", "content", "status", "score", "broadcast_date", "note", "source", "created_at", "updated_at", "song_name", "artist", "song_url", "period", "broadcast_time")
        for (row in rows) {
            require(row.path("status").asText() in statuses) { "旧投稿状态不正确" }
            val values: List<Any?> = names.map { name ->
                when (name) {
                    "period" -> row.path(name).asText().ifBlank { Weeks.key(OffsetDateTime.parse(row.path("created_at").asText()).atZoneSameInstant(schoolZone).toLocalDate()) }
                    "id" -> row.path(name).asLong().also { require(it > 0) }
                    "score" -> if (row.path(name).isNull || row.path(name).isMissingNode) null else row.path(name).asInt().also { require(it in 0..100) }
                    "created_at", "updated_at" -> OffsetDateTime.parse(row.path(name).asText()).atZoneSameInstant(schoolZone).toOffsetDateTime().withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    else -> row.path(name).asText("")
                }
            }
            jdbc.update("INSERT INTO submissions(${names.joinToString(",")}) VALUES(${names.joinToString(",") { "?" }})", *values.toTypedArray())
        }
        for (counter in tree.path("counters")) {
            jdbc.update("INSERT INTO counters(day,n) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET n=excluded.n", counter.path("day").asText(), counter.path("n").asInt())
        }
        // 即使旧库的 counters 缺失，也从编号恢复当天最大计数。
        rows.forEach { row ->
            val code = row.path("code").asText()
            val day = OffsetDateTime.parse(row.path("created_at").asText()).atZoneSameInstant(schoolZone).toLocalDate().toString()
            val expectedPrefix = "GB${day.drop(2).replace("-", "")}"
            val n = if (code.startsWith(expectedPrefix)) code.drop(expectedPrefix.length).toIntOrNull() ?: 0 else 0
            jdbc.update("INSERT INTO counters(day,n) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET n=CASE WHEN counters.n>excluded.n THEN counters.n ELSE excluded.n END", day, n)
        }
        for (setting in tree.path("settings")) {
            val key = setting.path("key").asText()
            if (key in setOf("admin_hash", "admin_password_initial")) auth.setSetting(key, setting.path("value").asText())
        }
        jdbc.update("DELETE FROM sessions")
        if (storage == "postgres") jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('submissions','id'),COALESCE(MAX(id),1),MAX(id) IS NOT NULL) FROM submissions", Long::class.java)
        println("已导入 ${rows.size()} 条投稿，现有账号密码不变，旧登录会话已注销；不会自动创建账号。")
    }
}
