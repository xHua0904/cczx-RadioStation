package com.cczxcsc.service

import com.cczxcsc.common.ApiError
import com.cczxcsc.common.SongRequestContent
import com.cczxcsc.common.Weeks
import com.cczxcsc.common.categories
import com.cczxcsc.common.schoolClasses
import com.cczxcsc.common.statuses
import com.cczxcsc.model.SubmissionInput
import java.time.LocalDate

object Validation {
    fun publicClass(value: String): String {
        val name = text(value, "班级", min = 2, max = 30)
        val match = Regex("^高([一二])([0-9]+|[一二三四五六七八九十]+)班$").matchEntire(name)
            ?: throw ApiError(400, "请选择高一 1–11 班或高二 1–9 班")
        val number = match.groupValues[2].toIntOrNull()
            ?: listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一").indexOf(match.groupValues[2]).let { if (it >= 0) it + 1 else 0 }
        val canonical = "高${match.groupValues[1]}${number}班"
        if (canonical !in schoolClasses) throw ApiError(400, "请选择高一 1–11 班或高二 1–9 班")
        return canonical
    }
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
        val songRequest = listOf(input.songName, input.artist, input.message, input.reason).any { it.isNotBlank() }
        val songName = if (songRequest) text(input.songName, "点播歌曲名称", min = 1, max = 80) else ""
        val artist = if (songRequest) text(input.artist, "歌手", min = 1, max = 80) else ""
        val message = if (songRequest) text(input.message, "简短寄语", max = 100, required = false) else ""
        val reason = if (songRequest) text(input.reason, "点播理由", max = 300, required = false) else ""
        val content = if (songRequest) SongRequestContent.encode(message, reason) else text(input.content, "投稿内容", min = 1, max = 100)
        val period = input.period.ifBlank { Weeks.current().key }
        Weeks.parse(period)
        if (!admin && period !in Weeks.available().map { it.key }) throw ApiError(400, "请选择当前开放的投稿周次")
        val category = input.category.ifBlank { if (songRequest) "点歌祝福" else "其他" }
        if (category !in categories) throw ApiError(400, "栏目不正确")
        val suppliedTitle = text(input.title, "标题", max = 60, required = false)
        val title = if (songRequest) songName.take(60) else suppliedTitle.ifBlank { content.lineSequence().first().take(60) }
        if (input.status !in statuses) throw ApiError(400, "状态不合法")
        return input.copy(
            author = text(input.author, "姓名", min = 2, max = 20),
            className = if (admin) text(input.className, "班级", min = 2, max = 30) else publicClass(input.className),
            contact = text(input.contact, "联系方式", max = 50, required = false),
            category = category, title = title, content = content, songName = songName, artist = artist, songUrl = "", period = period, message = message, reason = reason,
            status = if (admin) input.status else "待审核", note = if (admin) text(input.note, "备注", max = 300, required = false) else ""
        )
    }
}
