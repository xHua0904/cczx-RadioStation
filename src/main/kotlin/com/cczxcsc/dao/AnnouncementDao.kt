package com.cczxcsc.dao

import com.cczxcsc.model.Announcement
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
class AnnouncementDao(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { row, _ ->
        Announcement(row.getLong("id"), row.getString("title"), row.getString("content"), row.getString("publisher"),
            row.getInt("published") == 1, row.getString("created_at"), row.getString("published_at"), row.getString("updated_by"), row.getString("updated_at"))
    }
    fun list(public: Boolean): List<Announcement> = jdbc.query(
        "SELECT * FROM announcements${if (public) " WHERE published=1" else ""} ORDER BY published_at DESC,id DESC", mapper
    )
    fun insert(title: String, content: String, actor: String, time: String): Long = jdbc.queryForObject(
        "INSERT INTO announcements(title,content,kind,period,publisher,published,created_at,published_at,updated_by,updated_at) VALUES(?,?,?,?,?,1,?,?,?,?) RETURNING id",
        Long::class.java, title, content, "公告", "", actor, time, time, actor, time
    )!!
    fun title(id: Long): String? = jdbc.queryForList("SELECT title FROM announcements WHERE id=?", id).firstOrNull()?.get("title")?.toString()
    fun visibility(id: Long, published: Boolean, actor: String, time: String): Boolean = jdbc.update(
        "UPDATE announcements SET published=?,updated_by=?,updated_at=? WHERE id=?", if (published) 1 else 0, actor, time, id
    ) == 1
    fun delete(id: Long): Boolean = jdbc.update("DELETE FROM announcements WHERE id=?", id) == 1
}
