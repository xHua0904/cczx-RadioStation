package com.cczxcsc.service

import com.cczxcsc.common.ApiError
import com.cczxcsc.common.Weeks
import com.cczxcsc.common.adoptedStatuses
import com.cczxcsc.common.categories
import com.cczxcsc.common.schoolClasses
import com.cczxcsc.common.schoolZone
import com.cczxcsc.common.statuses
import com.cczxcsc.dao.Filters
import com.cczxcsc.dao.SubmissionDao
import com.cczxcsc.model.BatchInput
import com.cczxcsc.model.ExportFile
import com.cczxcsc.model.ProgressInput
import com.cczxcsc.model.SubmissionInput
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.round
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SubmissionService(private val repo: SubmissionDao, private val audit: AuditService, private val exports: ExportService) {
    private fun filters(params: Map<String, String>): Filters {
        params["period"]?.takeIf { it.isNotBlank() }?.let { Weeks.parse(it) }
        for (key in listOf("start", "end")) params[key]?.takeIf { it.isNotEmpty() }?.let { Validation.date(it) }
        return repo.filters(params)
    }
    @Transactional
    fun create(input: SubmissionInput, admin: Boolean = false, actor: String = "系统"): Map<String, Any> {
        val result = repo.insert(Validation.submission(input, admin), if (admin) "手动登记" else "在线投稿")
        val row = repo.byCode(result["code"].toString())!!
        if (admin) {
            repo.update(row.id, mapOf("updated_by" to actor, "reviewed_by" to if (row.status == "待审核") "" else actor))
            audit.record(actor, "手动登记", "submission", row.id, row.code)
        } else audit.record("投稿人：${row.author}", "在线投稿", "submission", row.id, row.code)
        return result
    }

    fun meta(): Map<String, Any> {
        val start = LocalDate.now(schoolZone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        return mapOf("categories" to categories, "statuses" to statuses, "classes" to schoolClasses, "periods" to Weeks.available(), "total" to repo.count(), "this_week" to repo.count(filters(mapOf("start" to start))))
    }

    fun track(code: String, author: String): Map<String, Any?> {
        if (code.isBlank()) throw ApiError(400, "请输入投稿编号")
        if (author.isBlank()) throw ApiError(400, "请输入投稿时填写的姓名")
        val row = repo.byCode(code.trim().uppercase()) ?: throw ApiError(404, "编号与姓名不匹配，请核对后再试")
        if (row.author != author.trim()) throw ApiError(404, "编号与姓名不匹配，请核对后再试")
        // 保留旧查询 API 兼容性，不返回联系方式或正文。
        return mapOf("code" to row.code, "author" to row.author, "title" to row.title, "category" to row.category,
            "song_name" to row.songName, "artist" to row.artist,
            "status" to row.status, "note" to row.note, "broadcast_date" to row.broadcastDate,
            "created_at" to row.createdAt, "updated_at" to row.updatedAt,
            "period" to row.period, "broadcast_time" to row.broadcastTime)
    }

    @Transactional(readOnly = true)
    fun progress(input: ProgressInput): Map<String, Any> {
        val query = Validation.text(input.query, "姓名或稿件编号", min = 2, max = 40)
        val code = query.uppercase(Locale.ROOT)
        val byCode = Regex("^GB[0-9]+$").matches(code)
        val page = input.page.coerceIn(1, 1_000_000)
        return repo.progress(if (byCode) code else query, byCode, page) + ("input_type" to if (byCode) "code" else "name")
    }

    @Transactional(readOnly = true)
    fun publicSchedule(page: Int): Map<String, Any> = repo.publicSchedule(LocalDate.now(schoolZone).toString(), page.coerceIn(1, 1_000_000))

    @Transactional(readOnly = true)
    fun songExists(name: String): Map<String, Boolean> = mapOf("exists" to repo.songExists(Validation.text(name, "歌曲名称", min = 1, max = 80)))

    fun list(params: Map<String, String>): Map<String, Any> {
        val filters = filters(params)
        val page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val total = repo.count(filters)
        return mapOf("items" to repo.list(filters, page), "total" to total, "page" to page,
            "pages" to ((total + 14) / 15).coerceAtLeast(1), "page_size" to 15, "counts" to repo.counts(filters))
    }

    @Transactional
    fun update(id: Long, body: Map<String, Any?>, actor: String = "系统"): Map<String, Boolean> {
        if (body.containsKey("score")) throw ApiError(400, "评分功能已取消，请使用备注")
        val fields = linkedMapOf<String, Any?>()
        if (body.containsKey("status")) {
            val status = body["status"]?.toString().orEmpty()
            if (status !in statuses) throw ApiError(400, "状态不合法")
            fields["status"] = status
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
        val reviewChanged = status != existing.status && status in setOf("已通过", "已退稿", "待审核") && !(existing.status == "已播出" && status == "已通过")
        val scheduleChanged = (fields["broadcast_date"]?.toString() ?: existing.broadcastDate) != existing.broadcastDate ||
            (fields["broadcast_time"]?.toString() ?: existing.broadcastTime) != existing.broadcastTime
        fields["updated_by"] = actor
        if (reviewChanged) fields["reviewed_by"] = actor
        if (scheduleChanged) fields["scheduled_by"] = actor
        if (!repo.update(id, fields)) throw ApiError(404, "投稿不存在")
        if (reviewChanged) audit.record(actor, when (status) { "已通过" -> "审核通过"; "已退稿" -> "退稿"; else -> "重新提交审批" }, "submission", id, existing.code)
        if (scheduleChanged) audit.record(actor, if (fields["broadcast_date"] == "") "移出日程" else "安排播出日程", "submission", id, existing.code)
        if (!reviewChanged && !scheduleChanged) audit.record(actor, if (status != existing.status) "切换播出状态：$status" else "修改备注", "submission", id, existing.code)
        return mapOf("ok" to true)
    }

    @Transactional
    fun delete(id: Long, actor: String = "系统"): Map<String, Boolean> {
        val row = repo.byId(id) ?: throw ApiError(404, "投稿不存在")
        if (!repo.delete(id)) throw ApiError(404, "投稿不存在")
        audit.record(actor, "删除稿件", "submission", id, row.code)
        return mapOf("ok" to true)
    }

    @Transactional(readOnly = true)
    fun stats(): Map<String, Any> {
        val total = repo.count()
        val weekStart = LocalDate.now(schoolZone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val byStatus = repo.counts()
        val adopted = adoptedStatuses.sumOf { byStatus[it] ?: 0L }
        val weekly = (7 downTo 0).map { i ->
            val start = weekStart.minusWeeks(i.toLong())
            linkedMapOf<String, Any>("start" to start.toString(), "label" to "${start.monthValue}/${start.dayOfMonth}", "count" to 0L, "adopted" to 0L)
        }
        val weeks = weekly.associateBy { it["start"].toString() }
        repo.statusesSince(weekStart.minusWeeks(7).toString()).forEach { row ->
            val date = runCatching { LocalDate.parse(row["d"].toString()) }.getOrNull() ?: return@forEach
            val bucket = weeks[date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()] ?: return@forEach
            bucket["count"] = bucket["count"] as Long + 1L
            if (row["status"] in adoptedStatuses) bucket["adopted"] = bucket["adopted"] as Long + 1L
        }
        return mapOf("total" to total, "this_week" to repo.count(filters(mapOf("start" to weekStart.toString()))),
            "pending" to (byStatus["待审核"] ?: 0L), "adopted" to adopted, "broadcast" to (byStatus["已播出"] ?: 0L),
            "adoption_rate" to if (total == 0L) 0.0 else round(adopted.toDouble() / total * 1000) / 10,
            "by_status" to repo.statusGroups(), "by_category" to repo.categoryGroups(), "by_class" to repo.classRank(), "weekly" to weekly)
    }

    fun export(params: Map<String, String>, actor: String): ExportFile {
        val items = repo.list(filters(params))
        val file = exports.export(items, params["format"] ?: "csv")
        audit.record(actor, "导出稿件", "export", file.filename, "${file.filename}；共 ${items.size} 篇")
        return file
    }

    fun reviewSummary(): Map<String, Any> {
        val ids = repo.pendingIds()
        return mapOf("count" to ids.size, "ids" to ids)
    }

    @Transactional
    fun batch(body: BatchInput, actor: String = "系统"): Map<String, Any> {
        val ids = body.ids.distinct()
        if (ids.isEmpty() || ids.any { it <= 0 }) throw ApiError(400, "请先选择稿件")
        if (body.action !in setOf("approve", "reject", "schedule", "played", "clear_schedule", "reopen")) throw ApiError(400, "操作不正确")
        val rows = ids.map { repo.byId(it) ?: throw ApiError(404, "部分稿件已不存在，请刷新列表") }
        if (body.action in setOf("approve", "reject") && rows.any { it.status != "待审核" }) throw ApiError(409, "部分稿件已被审批，请刷新列表后重试")
        if (body.action == "schedule" && rows.any { it.status != "已通过" }) throw ApiError(400, "仅已通过稿件可以安排日程")
        if (body.action == "played" && rows.any { it.status != "已通过" }) throw ApiError(400, "仅已通过稿件可以标记已播出")
        if (body.action == "schedule" && body.broadcastDate.isBlank()) throw ApiError(400, "请选择播出日期")
        for (row in rows) {
            if (body.action in setOf("approve", "reject")) {
                val note = Validation.text(body.note, "备注", max = 300, required = false)
                if (!repo.reviewIfPending(row.id, if (body.action == "approve") "已通过" else "已退稿", note, actor)) throw ApiError(409, "稿件状态已改变，请刷新列表")
                audit.record(actor, if (body.action == "approve") "审核通过" else "退稿", "submission", row.id, row.code)
            } else {
                val fields: Map<String, Any?> = when (body.action) {
                    "schedule" -> mapOf("broadcast_date" to body.broadcastDate, "broadcast_time" to body.broadcastTime)
                    "played" -> mapOf("status" to "已播出")
                    "clear_schedule" -> mapOf("broadcast_date" to "", "broadcast_time" to "")
                    else -> mapOf("status" to "待审核")
                }
                update(row.id, fields, actor)
            }
        }
        return mapOf("ok" to true, "updated" to ids.size)
    }

    @Transactional
    fun approveAll(body: BatchInput, actor: String = "系统"): Map<String, Any> {
        if (!body.confirmed) throw ApiError(400, "全部审核属于风险操作，需要明确确认")
        val result = batch(body.copy(action = "approve"), actor)
        return result + ("remaining" to repo.count(filters(mapOf("phase" to "pending"))))
    }

    fun schedule(): Map<String, Any> = mapOf("items" to repo.scheduled().sortedWith(compareBy({ it.broadcastDate }, { it.broadcastTime }, { it.id })))
}
