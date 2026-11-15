package com.cczxcsc

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlin.math.round

@Service
class SubmissionService(private val repo: SubmissionRepository) {
    @Transactional
    fun create(input: SubmissionInput, admin: Boolean = false) = repo.insert(Validation.submission(input, admin), if (admin) "手动登记" else "在线投稿")

    fun meta(): Map<String, Any> {
        val start = LocalDate.now(schoolZone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        return mapOf("categories" to categories, "statuses" to statuses, "periods" to Weeks.available(), "total" to repo.count(), "this_week" to repo.count(repo.filters(mapOf("start" to start))))
    }

    fun track(code: String, author: String): Map<String, Any?> {
        if (code.isBlank()) throw ApiError(400, "请输入投稿编号")
        if (author.isBlank()) throw ApiError(400, "请输入投稿时填写的姓名")
        val row = repo.byCode(code.trim().uppercase()) ?: throw ApiError(404, "编号与姓名不匹配，请核对后再试")
        if (row.author != author.trim()) throw ApiError(404, "编号与姓名不匹配，请核对后再试")
        // 保留旧查询 API 兼容性，不返回联系方式或正文。
        return mapOf("code" to row.code, "author" to row.author, "title" to row.title, "category" to row.category,
            "status" to row.status, "note" to row.note, "score" to row.score, "broadcast_date" to row.broadcastDate,
            "created_at" to row.createdAt, "updated_at" to row.updatedAt,
            "period" to row.period, "broadcast_time" to row.broadcastTime)
    }

    fun list(params: Map<String, String>): Map<String, Any> {
        val filters = repo.filters(params)
        val page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val total = repo.count(filters)
        return mapOf("items" to repo.list(filters, page), "total" to total, "page" to page,
            "pages" to ((total + 14) / 15).coerceAtLeast(1), "page_size" to 15, "counts" to repo.counts(filters))
    }

    @Transactional
    fun update(id: Long, body: Map<String, Any?>): Map<String, Boolean> {
        val fields = linkedMapOf<String, Any?>()
        if (body.containsKey("status")) {
            val status = body["status"]?.toString().orEmpty()
            if (status !in statuses) throw ApiError(400, "状态不合法")
            fields["status"] = status
        }
        if (body.containsKey("score")) {
            val raw = body["score"]
            val score = if (raw == null || raw == "") null else raw.toString().toIntOrNull() ?: throw ApiError(400, "评分需为整数")
            if (score != null && score !in 0..100) throw ApiError(400, "评分需在 0–100 之间")
            fields["score"] = score
        }
        if (body.containsKey("broadcast_date")) fields["broadcast_date"] = Validation.date(body["broadcast_date"]?.toString().orEmpty().trim())
        if (body.containsKey("broadcast_time")) {
            val time = body["broadcast_time"]?.toString().orEmpty()
            if (time.isNotBlank() && !Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time)) throw ApiError(400, "播出时间应为 HH:mm")
            fields["broadcast_time"] = time
        }
        if (body.containsKey("note")) fields["note"] = Validation.text(body["note"]?.toString(), "备注", max = 300, required = false)
        if (fields.isEmpty()) throw ApiError(400, "没有需要更新的字段")
        val existing = repo.byId(id) ?: throw ApiError(404, "投稿不存在")
        val status = fields["status"]?.toString() ?: existing.status
        val date = fields["broadcast_date"]?.toString() ?: existing.broadcastDate
        if (fields["broadcast_date"] != null && fields["broadcast_date"].toString().isNotBlank() && status !in adoptedStatuses) throw ApiError(400, "仅已通过或已播出稿件可以添加日程")
        if (fields.containsKey("broadcast_time") && fields["broadcast_time"].toString().isNotBlank() && date.isBlank()) throw ApiError(400, "请先设置播出日期")
        if (status !in adoptedStatuses || date.isEmpty()) { fields["broadcast_date"] = ""; fields["broadcast_time"] = "" }
        if (!repo.update(id, fields)) throw ApiError(404, "投稿不存在")
        return mapOf("ok" to true)
    }

    fun delete(id: Long): Map<String, Boolean> {
        if (!repo.delete(id)) throw ApiError(404, "投稿不存在")
        return mapOf("ok" to true)
    }

    @Transactional(readOnly = true)
    fun stats(): Map<String, Any> {
        val jdbc = repo.jdbc
        val total = repo.count()
        val weekStart = LocalDate.now(schoolZone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun groups(column: String) = jdbc.queryForList("SELECT $column AS name,COUNT(*) AS count FROM submissions GROUP BY $column ORDER BY count DESC,name")
        val byStatus = repo.counts(Filters("", emptyList()))
        val adopted = adoptedStatuses.sumOf { byStatus[it] ?: 0L }
        val classes = jdbc.queryForList("""SELECT class_name AS name,COUNT(*) AS count,
            SUM(CASE WHEN status IN ('已通过','已播出') THEN 1 ELSE 0 END) AS adopted
            FROM submissions GROUP BY class_name ORDER BY count DESC,class_name LIMIT 8""")
        val weekly = (7 downTo 0).map { i ->
            val start = weekStart.minusWeeks(i.toLong())
            linkedMapOf<String, Any>("start" to start.toString(), "label" to "${start.monthValue}/${start.dayOfMonth}", "count" to 0L, "adopted" to 0L)
        }
        val weeks = weekly.associateBy { it["start"].toString() }
        jdbc.queryForList("SELECT substr(created_at,1,10) AS d,status FROM submissions WHERE substr(created_at,1,10)>=?", weekStart.minusWeeks(7).toString()).forEach { row ->
            val date = runCatching { LocalDate.parse(row["d"].toString()) }.getOrNull() ?: return@forEach
            val bucket = weeks[date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()] ?: return@forEach
            bucket["count"] = bucket["count"] as Long + 1L
            if (row["status"] in adoptedStatuses) bucket["adopted"] = bucket["adopted"] as Long + 1L
        }
        return mapOf("total" to total, "this_week" to repo.count(repo.filters(mapOf("start" to weekStart.toString()))),
            "pending" to (byStatus["待审核"] ?: 0L), "adopted" to adopted, "broadcast" to (byStatus["已播出"] ?: 0L),
            "adoption_rate" to if (total == 0L) 0.0 else round(adopted.toDouble() / total * 1000) / 10,
            "by_status" to groups("status"), "by_category" to groups("category"), "by_class" to classes, "weekly" to weekly)
    }

    fun exportItems(params: Map<String, String>): List<Submission> = repo.list(repo.filters(params))

    fun reviewSummary(): Map<String, Any> {
        val ids = repo.jdbc.queryForList("SELECT id FROM submissions WHERE status='待审核' ORDER BY id", Long::class.java)
        return mapOf("count" to ids.size, "ids" to ids)
    }

    @Transactional
    fun batch(body: BatchInput): Map<String, Any> {
        val ids = body.ids.distinct()
        if (ids.isEmpty() || ids.any { it <= 0 }) throw ApiError(400, "请先选择稿件")
        if (body.action !in setOf("approve", "reject", "schedule", "played", "clear_schedule", "reopen")) throw ApiError(400, "操作不正确")
        val rows = ids.map { repo.byId(it) ?: throw ApiError(404, "部分稿件已不存在，请刷新列表") }
        if (body.action in setOf("approve", "reject") && rows.any { it.status != "待审核" }) throw ApiError(409, "部分稿件已被审批，请刷新列表后重试")
        if (body.action == "schedule" && rows.any { it.status != "已通过" }) throw ApiError(400, "仅已通过稿件可以安排日程")
        if (body.action == "played" && rows.any { it.status != "已通过" }) throw ApiError(400, "仅已通过稿件可以标记已播出")
        if (body.action == "schedule" && body.broadcastDate.isBlank()) throw ApiError(400, "请选择播出日期")
        for (row in rows) {
            val fields: Map<String, Any?> = when (body.action) {
                "approve" -> mapOf("status" to "已通过", "note" to body.note)
                "reject" -> mapOf("status" to "已退稿", "note" to body.note)
                "schedule" -> mapOf("broadcast_date" to body.broadcastDate, "broadcast_time" to body.broadcastTime)
                "played" -> mapOf("status" to "已播出")
                "clear_schedule" -> mapOf("broadcast_date" to "", "broadcast_time" to "")
                else -> mapOf("status" to "待审核")
            }
            if (body.action in setOf("approve", "reject")) {
                val note = Validation.text(body.note, "备注", max = 300, required = false)
                val changed = repo.jdbc.update("UPDATE submissions SET status=?,note=?,broadcast_date='',broadcast_time='',updated_at=? WHERE id=? AND status='待审核'", fields["status"], note, nowIso(), row.id)
                if (changed != 1) throw ApiError(409, "稿件状态已改变，请刷新列表")
            } else update(row.id, fields)
        }
        return mapOf("ok" to true, "updated" to ids.size)
    }

    @Transactional
    fun approveAll(body: BatchInput): Map<String, Any> {
        if (!body.confirmed) throw ApiError(400, "全部审核属于风险操作，需要明确确认")
        val result = batch(body.copy(action = "approve"))
        return result + ("remaining" to repo.count(repo.filters(mapOf("phase" to "pending"))))
    }

    fun schedule(): Map<String, Any> = mapOf("items" to repo.list(Filters(" WHERE broadcast_date<>'' AND status IN ('已通过','已播出')", emptyList())).sortedWith(compareBy({ it.broadcastDate }, { it.broadcastTime }, { it.id })))
}

data class BatchInput(val ids: List<Long> = emptyList(), val action: String = "", val note: String = "", val broadcastDate: String = "", val broadcastTime: String = "", val confirmed: Boolean = false)
