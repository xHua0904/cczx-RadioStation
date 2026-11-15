package com.cczxcsc

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val schoolZone: ZoneId = ZoneId.of("Asia/Shanghai")
val categories = listOf("校园新闻", "美文朗读", "点歌祝福", "失物招领", "活动通知", "人物访谈", "英语角", "其他")
val statuses = listOf("待审核", "已通过", "已播出", "已退稿")
val adoptedStatuses = setOf("已通过", "已播出")
fun nowIso(): String = OffsetDateTime.now(schoolZone).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

class ApiError(val status: Int, override val message: String) : RuntimeException(message)

data class SubmissionInput(
    val author: String = "", val className: String = "", val contact: String = "",
    val category: String = "", val title: String = "", val content: String = "",
    val songName: String = "", val artist: String = "", val songUrl: String = "",
    val status: String = "待审核", val note: String = "", val period: String = ""
)

data class Submission(
    val id: Long, val code: String, val author: String, val className: String,
    val contact: String, val category: String, val title: String, val content: String,
    val status: String, val score: Int?, val broadcastDate: String, val note: String,
    val source: String, val createdAt: String, val updatedAt: String,
    val songName: String, val artist: String, val songUrl: String,
    val period: String = "", val broadcastTime: String = ""
)

object Validation {
    fun text(value: String?, field: String, min: Int = 0, max: Int = 500, required: Boolean = true): String {
        val cleaned = (value ?: "").replace("\r\n", "\n").replace('\r', '\n')
            .filter { it == '\n' || it == '\t' || it.code >= 32 }.trim()
        if (required && cleaned.isEmpty()) throw ApiError(400, "请填写$field")
        if (cleaned.length < min) throw ApiError(400, "$field 至少需要 $min 个字")
        if (cleaned.length > max) throw ApiError(400, "$field 不能超过 $max 个字")
        return cleaned
    }

    fun date(value: String): String {
        if (value.isNotBlank() && !runCatching { LocalDate.parse(value) }.isSuccess) throw ApiError(400, "播出日期格式应为 YYYY-MM-DD")
        return value
    }

    fun submission(input: SubmissionInput, admin: Boolean = false): SubmissionInput {
        val content = text(input.content, "投稿内容", min = 1, max = 100)
        val period = input.period.ifBlank { Weeks.current().key }
        Weeks.parse(period)
        if (!admin && period !in Weeks.available().map { it.key }) throw ApiError(400, "请选择当前开放的投稿周次")
        val category = input.category.ifBlank { "其他" }
        if (category !in categories) throw ApiError(400, "栏目不正确")
        val suppliedTitle = text(input.title, "标题", max = 60, required = false)
        val title = suppliedTitle.ifBlank { content.lineSequence().first().take(60) }
        if (input.status !in statuses) throw ApiError(400, "状态不合法")
        return input.copy(
            author = text(input.author, "姓名", min = 2, max = 20),
            className = text(input.className, "班级", min = 2, max = 30),
            contact = text(input.contact, "联系方式", max = 50, required = false),
            category = category, title = title, content = content, songName = "", artist = "", songUrl = "", period = period,
            status = if (admin) input.status else "待审核", note = if (admin) text(input.note, "备注", max = 300, required = false) else ""
        )
    }
}
