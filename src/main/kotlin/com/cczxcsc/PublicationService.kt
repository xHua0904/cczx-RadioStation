package com.cczxcsc

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

data class AnnouncementInput(val title: String = "", val content: String = "", val publishAs: String = "current_account")
data class Announcement(val id: Long, val title: String, val content: String,
    val publisher: String, val published: Boolean, val createdAt: String, val publishedAt: String,
    val updatedBy: String = "", val updatedAt: String = "")

@Service
class PublicationService(private val jdbc: JdbcTemplate, private val audit: AuditService) {
    private val mapper = RowMapper { row: ResultSet, _: Int ->
        Announcement(row.getLong("id"), row.getString("title"), row.getString("content"),
            row.getString("publisher"), row.getInt("published") == 1, row.getString("created_at"), row.getString("published_at"), row.getString("updated_by"), row.getString("updated_at"))
    }
    fun list(public: Boolean = true): Map<String, Any> = mapOf("items" to jdbc.query(
        "SELECT * FROM announcements${if (public) " WHERE published=1" else ""} ORDER BY published_at DESC,id DESC", mapper))

    @Transactional
    fun publish(input: AnnouncementInput, actor: String): Map<String, Any> {
        val title = Validation.text(input.title, "公告标题", min = 1, max = 80)
        val content = Validation.text(input.content, "公告内容", min = 1, max = 2000)
        val publisher = when (input.publishAs) {
            "current_account" -> actor
            "media_center" -> "新媒体中心"
            else -> throw ApiError(400, "发布身份不正确")
        }
        val time = nowIso()
        val id = jdbc.queryForObject("INSERT INTO announcements(title,content,kind,period,publisher,published,created_at,published_at,updated_by,updated_at) VALUES(?,?,?,?,?,1,?,?,?,?) RETURNING id",
            Long::class.java, title, content, "公告", "", publisher, time, time, actor, time)!!
        audit.record(actor, "发布公告", "announcement", id, "$title；发布者：$publisher")
        return mapOf("ok" to true, "id" to id)
    }

    @Transactional
    fun visibility(id: Long, published: Boolean, actor: String): Map<String, Boolean> {
        val title = jdbc.queryForList("SELECT title FROM announcements WHERE id=?", id).firstOrNull()?.get("title")?.toString() ?: throw ApiError(404, "消息不存在")
        val updated = jdbc.update("UPDATE announcements SET published=?,updated_by=?,updated_at=? WHERE id=?", if (published) 1 else 0, actor, nowIso(), id)
        if (updated == 0) throw ApiError(404, "消息不存在")
        audit.record(actor, if (published) "恢复发布" else "撤回消息", "announcement", id, title)
        return mapOf("ok" to true)
    }
    @Transactional
    fun delete(id: Long, actor: String): Map<String, Boolean> {
        val title = jdbc.queryForList("SELECT title FROM announcements WHERE id=?", id).firstOrNull()?.get("title")?.toString() ?: throw ApiError(404, "消息不存在")
        if (jdbc.update("DELETE FROM announcements WHERE id=?", id) == 0) throw ApiError(404, "消息不存在")
        audit.record(actor, "删除消息", "announcement", id, title)
        return mapOf("ok" to true)
    }
}
