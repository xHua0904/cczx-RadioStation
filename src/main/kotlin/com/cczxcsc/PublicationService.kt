package com.cczxcsc

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

data class AnnouncementInput(val title: String = "", val content: String = "", val kind: String = "公告", val period: String = "")
data class Announcement(val id: Long, val title: String, val content: String, val kind: String, val period: String,
    val publisher: String, val published: Boolean, val createdAt: String, val publishedAt: String)

@Service
class PublicationService(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { row: ResultSet, _: Int ->
        Announcement(row.getLong("id"), row.getString("title"), row.getString("content"), row.getString("kind"), row.getString("period"),
            "新媒体中心", row.getInt("published") == 1, row.getString("created_at"), row.getString("published_at"))
    }
    fun list(public: Boolean = true): Map<String, Any> = mapOf("items" to jdbc.query(
        "SELECT * FROM announcements${if (public) " WHERE published=1" else ""} ORDER BY published_at DESC,id DESC", mapper))

    @Transactional
    fun publish(input: AnnouncementInput): Map<String, Any> {
        val title = Validation.text(input.title, "消息标题", min = 1, max = 80)
        val content = Validation.text(input.content, "消息内容", min = 1, max = 2000)
        if (input.kind !in setOf("公告", "投稿计划")) throw ApiError(400, "消息类型不正确")
        val period = input.period.trim()
        if (period.isNotEmpty()) Weeks.parse(period)
        val time = nowIso()
        val id = jdbc.queryForObject("INSERT INTO announcements(title,content,kind,period,publisher,published,created_at,published_at) VALUES(?,?,?,?,'新媒体中心',1,?,?) RETURNING id",
            Long::class.java, title, content, input.kind, period, time, time)!!
        return mapOf("ok" to true, "id" to id)
    }

    fun visibility(id: Long, published: Boolean): Map<String, Boolean> {
        val updated = jdbc.update("UPDATE announcements SET published=? WHERE id=?", if (published) 1 else 0, id)
        if (updated == 0) throw ApiError(404, "消息不存在")
        return mapOf("ok" to true)
    }
    fun delete(id: Long): Map<String, Boolean> {
        if (jdbc.update("DELETE FROM announcements WHERE id=?", id) == 0) throw ApiError(404, "消息不存在")
        return mapOf("ok" to true)
    }
}
