package com.cczxcsc

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

data class Filters(val where: String, val args: List<Any>)

@Repository
class SubmissionRepository(val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { rs: ResultSet, _: Int ->
        Submission(
            rs.getLong("id"), rs.getString("code"), rs.getString("author"), rs.getString("class_name"),
            rs.getString("contact"), rs.getString("category"), rs.getString("title"), rs.getString("content"),
            rs.getString("status"), (rs.getObject("score") as? Number)?.toInt(), rs.getString("broadcast_date"), rs.getString("note"),
            rs.getString("source"), rs.getString("created_at"), rs.getString("updated_at"),
            rs.getString("song_name"), rs.getString("artist"), rs.getString("song_url"), rs.getString("period"), rs.getString("broadcast_time")
        )
    }

    fun count(filters: Filters = Filters("", emptyList())): Long =
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

    fun list(filters: Filters, page: Int? = null): List<Submission> {
        val suffix = if (page == null) " ORDER BY id ASC" else " ORDER BY id DESC LIMIT ? OFFSET ?"
        val args = filters.args + if (page == null) emptyList() else listOf(15, (page.toLong() - 1) * 15)
        return jdbc.query("SELECT * FROM submissions${filters.where}$suffix", mapper, *args.toTypedArray())
    }

    fun counts(filters: Filters): Map<String, Long> = jdbc.queryForList(
        "SELECT status,COUNT(*) AS n FROM submissions${filters.where} GROUP BY status", *filters.args.toTypedArray()
    ).associate { it["status"].toString() to (it["n"] as Number).toLong() }

    fun update(id: Long, fields: Map<String, Any?>): Boolean {
        val assignments = fields.keys.joinToString(",") { "$it=?" }
        return jdbc.update("UPDATE submissions SET $assignments,updated_at=? WHERE id=?", *(fields.values + listOf(nowIso(), id)).toTypedArray()) > 0
    }

    fun delete(id: Long): Boolean = jdbc.update("DELETE FROM submissions WHERE id=?", id) > 0

    fun filters(params: Map<String, String>): Filters {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        val q = params["q"].orEmpty().trim().take(200)
        if (q.isNotEmpty()) {
            val names = listOf("code", "author", "class_name", "title", "content")
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
        params["period"]?.takeIf { it.isNotBlank() }?.let { Weeks.parse(it); clauses += "period=?"; args += it }
        for ((name, comparison) in listOf("start" to ">=", "end" to "<=")) {
            val value = params[name].orEmpty()
            if (value.isNotEmpty()) { Validation.date(value); clauses += "substr(created_at,1,10) $comparison ?"; args += value }
        }
        return Filters(if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND "), args)
    }
}
