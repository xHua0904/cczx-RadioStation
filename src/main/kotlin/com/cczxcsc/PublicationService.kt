package com.cczxcsc

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

data class AnnouncementInput(val title: String = "", val content: String = "", val kind: String = "公告", val period: String = "")
data class Announcement(val id: Long, val title: String, val content: String, val kind: String, val period: String,
    val publisher: String, val published: Boolean, val createdAt: String, val publishedAt: String,
    val updatedBy: String = "", val updatedAt: String = "")

@Service
class PublicationService(private val jdbc: JdbcTemplate, private val audit: AuditService) {
    private val mapper = RowMapper { row: ResultSet, _: Int ->
        Announcement(row.getLong("id"), row.getString("title"), row.getString("content"), row.getString("kind"), row.getString("period"),
            row.getString("publisher"), row.getInt("published") == 1, row.getString("created_at"), row.getString("published_at"), row.getString("updated_by"), row.getString("updated_at"))
    }
    fun list(public: Boolean = true): Map<String, Any> = mapOf("items" to jdbc.query(
        "SELECT * FROM announcements${if (public) " WHERE published=1" else ""} ORDER BY published_at DESC,id DESC", mapper))

    @Transactional
    fun publish(input: AnnouncementInput, actor: String = Passwords.DEFAULT_ACCOUNT): Map<String, Any> {
        val title = Validation.text(input.title, "消息标题", min = 1, max = 80)
        val content = Validation.text(input.content, "消息内容", min = 1, max = 2000)
        if (input.kind !in setOf("公告", "投稿计划")) throw ApiError(400, "消息类型不正确")
        val period = input.period.trim()
        if (period.isNotEmpty()) Weeks.parse(period)
        val time = nowIso()
        val id = jdbc.queryForObject("INSERT INTO announcements(title,content,kind,period,publisher,published,created_at,published_at,updated_by,updated_at) VALUES(?,?,?,?,?,1,?,?,?,?) RETURNING id",
            Long::class.java, title, content, input.kind, period, actor, time, time, actor, time)!!
        audit.record(actor, "发布${input.kind}", "announcement", id, title)
        return mapOf("ok" to true, "id" to id)
    }

    @Transactional
    fun visibility(id: Long, published: Boolean, actor: String = Passwords.DEFAULT_ACCOUNT): Map<String, Boolean> {
        val title = jdbc.queryForList("SELECT title FROM announcements WHERE id=?", id).firstOrNull()?.get("title")?.toString() ?: throw ApiError(404, "消息不存在")
        val updated = jdbc.update("UPDATE announcements SET published=?,updated_by=?,updated_at=? WHERE id=?", if (published) 1 else 0, actor, nowIso(), id)
        if (updated == 0) throw ApiError(404, "消息不存在")
        audit.record(actor, if (published) "恢复发布" else "撤回消息", "announcement", id, title)
        return mapOf("ok" to true)
    }
    @Transactional
    fun delete(id: Long, actor: String = Passwords.DEFAULT_ACCOUNT): Map<String, Boolean> {
        val title = jdbc.queryForList("SELECT title FROM announcements WHERE id=?", id).firstOrNull()?.get("title")?.toString() ?: throw ApiError(404, "消息不存在")
        if (jdbc.update("DELETE FROM announcements WHERE id=?", id) == 0) throw ApiError(404, "消息不存在")
        audit.record(actor, "删除消息", "announcement", id, title)
        return mapOf("ok" to true)
    }
}
