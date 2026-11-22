package com.cczxcsc.dao

import com.cczxcsc.common.SongRequestContent
import com.cczxcsc.common.categories
import com.cczxcsc.common.nowIso
import com.cczxcsc.common.statuses
import com.cczxcsc.model.Submission
import com.cczxcsc.model.SubmissionInput
import java.sql.ResultSet
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

data class Filters(val where: String = "", val args: List<Any> = emptyList())

@Repository
class SubmissionDao(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { rs: ResultSet, _: Int ->
        val storedContent = rs.getString("content")
        val notes = if (rs.getString("song_name").isNotBlank() && rs.getString("artist").isNotBlank()) SongRequestContent.decode(storedContent) else null
        val content = if (notes == null) storedContent else SongRequestContent.summary(rs.getString("song_name"), rs.getString("artist"), notes)
        Submission(
            rs.getLong("id"), rs.getString("code"), rs.getString("author"), rs.getString("class_name"),
            rs.getString("contact"), rs.getString("category"), rs.getString("title"), content,
            rs.getString("status"), rs.getString("broadcast_date"), rs.getString("note"),
            rs.getString("source"), rs.getString("created_at"), rs.getString("updated_at"),
            rs.getString("song_name"), rs.getString("artist"), rs.getString("song_url"), rs.getString("period"), rs.getString("broadcast_time"),
            rs.getString("reviewed_by"), rs.getString("scheduled_by"), rs.getString("updated_by"), notes?.message.orEmpty(), notes?.reason.orEmpty(), if (notes == null) storedContent else ""
        )
    }

    fun count(filters: Filters = Filters()): Long =
        jdbc.queryForObject("SELECT COUNT(*) FROM submissions${filters.where}", Long::class.java, *filters.args.toTypedArray()) ?: 0

    fun nextCode(day: String): String {
        // 数据库原子递增，多个请求或多个 PostgreSQL 实例不会生成相同编号。
        val n = jdbc.queryForObject("INSERT INTO counters(day,n) VALUES(?,1) ON CONFLICT(day) DO UPDATE SET n=counters.n+1 RETURNING n", Int::class.java, day)!!
        return "GB${day.drop(2).replace("-", "")}${n.toString().padStart(3, '0')}"
    }

    fun insert(input: SubmissionInput, source: String): Map<String, Any> {
        val time = nowIso()
        val code = nextCode(time.take(10))
        jdbc.update("""INSERT INTO submissions(code,author,class_name,contact,category,title,content,status,note,source,created_at,updated_at,song_name,artist,song_url,period)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", code, input.author, input.className, input.contact, input.category,
            input.title, input.content, input.status, input.note, source, time, time, input.songName, input.artist, input.songUrl, input.period)
        return mapOf("ok" to true, "code" to code, "created_at" to time)
    }

    fun byCode(code: String): Submission? = jdbc.query("SELECT * FROM submissions WHERE code=?", mapper, code).firstOrNull()
    fun byId(id: Long): Submission? = jdbc.query("SELECT * FROM submissions WHERE id=?", mapper, id).firstOrNull()

    // 公开查询只选择进度字段，不读取联系方式、寄语、理由、原文和管理备注。
    fun progress(query: String, byCode: Boolean, page: Int): Map<String, Any> {
        val column = if (byCode) "code" else "author"
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM submissions WHERE $column=?", Long::class.java, query) ?: 0L
        val items = jdbc.queryForList("SELECT code,class_name,song_name,artist,status,broadcast_date,broadcast_time,created_at FROM submissions WHERE $column=? ORDER BY id DESC LIMIT 10 OFFSET ?", query, (page - 1) * 10)
        return mapOf("items" to items, "total" to count, "page" to page, "pages" to ((count + 9) / 10).coerceAtLeast(1), "page_size" to 10)
    }

    fun publicSchedule(start: String, page: Int): Map<String, Any> {
        val where = " WHERE broadcast_date>=? AND status IN ('已通过','已播出')"
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM submissions$where", Long::class.java, start) ?: 0L
        val items = jdbc.queryForList("SELECT song_name,artist,status,broadcast_date,broadcast_time FROM submissions$where ORDER BY broadcast_date,broadcast_time,id LIMIT 20 OFFSET ?", start, (page - 1) * 20)
        return mapOf("items" to items, "total" to count, "page" to page, "pages" to ((count + 19) / 20).coerceAtLeast(1), "page_size" to 20)
    }

    fun songExists(name: String): Boolean = jdbc.queryForList(
        "SELECT 1 FROM submissions WHERE lower(trim(song_name))=lower(?) LIMIT 1", name
    ).isNotEmpty()

    fun list(filters: Filters, page: Int? = null): List<Submission> {
        val suffix = if (page == null) " ORDER BY id ASC" else " ORDER BY id DESC LIMIT ? OFFSET ?"
        val args = filters.args + if (page == null) emptyList() else listOf(15, (page.toLong() - 1) * 15)
        return jdbc.query("SELECT * FROM submissions${filters.where}$suffix", mapper, *args.toTypedArray())
    }

    fun counts(filters: Filters = Filters()): Map<String, Long> = jdbc.queryForList(
        "SELECT status,COUNT(*) AS n FROM submissions${filters.where} GROUP BY status", *filters.args.toTypedArray()
    ).associate { it["status"].toString() to (it["n"] as Number).toLong() }

    fun update(id: Long, fields: Map<String, Any?>): Boolean {
        val assignments = fields.keys.joinToString(",") { "$it=?" }
        return jdbc.update("UPDATE submissions SET $assignments,updated_at=? WHERE id=?", *(fields.values + listOf(nowIso(), id)).toTypedArray()) > 0
    }

    fun delete(id: Long): Boolean = jdbc.update("DELETE FROM submissions WHERE id=?", id) > 0

    private fun groups(column: String): List<Map<String, Any>> = jdbc.queryForList(
        "SELECT $column AS name,COUNT(*) AS count FROM submissions GROUP BY $column ORDER BY count DESC,name"
    )
    fun statusGroups() = groups("status")
    fun categoryGroups() = groups("category")
    fun classRank(): List<Map<String, Any>> = jdbc.queryForList("""SELECT class_name AS name,COUNT(*) AS count,
        SUM(CASE WHEN status IN ('已通过','已播出') THEN 1 ELSE 0 END) AS adopted
        FROM submissions GROUP BY class_name ORDER BY count DESC,class_name LIMIT 8""")
    fun statusesSince(start: String): List<Map<String, Any>> = jdbc.queryForList(
        "SELECT substr(created_at,1,10) AS d,status FROM submissions WHERE substr(created_at,1,10)>=?", start
    )
    fun pendingIds(): List<Long> = jdbc.queryForList("SELECT id FROM submissions WHERE status='待审核' ORDER BY id", Long::class.java)
    fun scheduled(): List<Submission> = list(filters(mapOf("schedule" to "scheduled")))
    fun reviewIfPending(id: Long, status: String, note: String, actor: String): Boolean = jdbc.update(
        "UPDATE submissions SET status=?,note=?,broadcast_date='',broadcast_time='',reviewed_by=?,updated_by=?,updated_at=? WHERE id=? AND status='待审核'",
        status, note, actor, actor, nowIso(), id
    ) == 1

    fun filters(params: Map<String, String>): Filters {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        val q = params["q"].orEmpty().trim().take(200)
        if (q.isNotEmpty()) {
            val names = listOf("code", "author", "class_name", "title", "song_name", "artist", "content")
            clauses += names.joinToString(" OR ", "(", ")") { "$it LIKE ?" }
            names.forEach { args += "%$q%" }
        }
        for ((name, allowed) in listOf("status" to statuses, "category" to categories)) {
            val value = params[name].orEmpty()
            if (value in allowed) { clauses += "$name=?"; args += value }
        }
        when (params["phase"]) {
            "pending" -> clauses += "status='待审核'"
            "reviewed" -> clauses += "status<>'待审核'"
        }
        // 在数据库中区分排期，确保搜索、分页和导出使用同一范围。
        when (params["schedule"]) {
            "unscheduled" -> clauses += "broadcast_date=''"
            "scheduled" -> clauses += "broadcast_date<>'' AND status IN ('已通过','已播出')"
        }
        params["period"]?.takeIf { it.isNotBlank() }?.let { clauses += "period=?"; args += it }
        for ((name, comparison) in listOf("start" to ">=", "end" to "<=")) {
            val value = params[name].orEmpty()
            if (value.isNotEmpty()) { clauses += "substr(created_at,1,10) $comparison ?"; args += value }
        }
        return Filters(if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND "), args)
    }
}
