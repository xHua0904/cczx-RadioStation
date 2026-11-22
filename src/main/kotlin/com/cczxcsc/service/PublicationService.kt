package com.cczxcsc.service

import com.cczxcsc.common.ApiError
import com.cczxcsc.common.nowIso
import com.cczxcsc.dao.AnnouncementDao
import com.cczxcsc.model.AnnouncementInput
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PublicationService(private val announcements: AnnouncementDao, private val audit: AuditService) {
    fun list(public: Boolean = true): Map<String, Any> = mapOf("items" to announcements.list(public))

    @Transactional
    fun publish(input: AnnouncementInput, actor: String): Map<String, Any> {
        val title = Validation.text(input.title, "公告标题", min = 1, max = 80)
        val content = Validation.text(input.content, "公告内容", min = 1, max = 2000)
        val id = announcements.insert(title, content, actor, nowIso())
        audit.record(actor, "发布公告", "announcement", id, "$title；发布者：$actor")
        return mapOf("ok" to true, "id" to id)
    }

    @Transactional
    fun visibility(id: Long, published: Boolean, actor: String): Map<String, Boolean> {
        val title = announcements.title(id) ?: throw ApiError(404, "消息不存在")
        if (!announcements.visibility(id, published, actor, nowIso())) throw ApiError(404, "消息不存在")
        audit.record(actor, if (published) "恢复发布" else "撤回消息", "announcement", id, title)
        return mapOf("ok" to true)
    }

    @Transactional
    fun delete(id: Long, actor: String): Map<String, Boolean> {
        val title = announcements.title(id) ?: throw ApiError(404, "消息不存在")
        if (!announcements.delete(id)) throw ApiError(404, "消息不存在")
        audit.record(actor, "删除消息", "announcement", id, title)
        return mapOf("ok" to true)
    }
}
