package com.cczxcsc.service

import com.cczxcsc.common.ApiError
import com.cczxcsc.common.nowIso
import com.cczxcsc.dao.AuditDao
import java.time.LocalDate
import org.springframework.stereotype.Service

// 操作人来自已验证的登录会话；与业务写入共用事务，失败操作不留下成功记录。
@Service
class AuditService(private val audit: AuditDao) {
    companion object { val commandActor: String get() = "命令行：${System.getProperty("user.name", "未知用户")}" }
    fun record(actor: String, action: String, type: String, id: Any, label: String) {
        audit.insert(actor, action, type, id.toString(), label, nowIso())
    }

    fun list(params: Map<String, String>): Map<String, Any> {
        val page = params["page"]?.toIntOrNull()?.coerceIn(1, 1_000_000) ?: 1
        val operation = params["operation"].orEmpty().trim()
        if (operation.isNotEmpty() && operation !in AuditDao.operationActions) throw ApiError(400, "操作类型不正确")
        val query = params["q"].orEmpty().trim()
        if (query.length > 100) throw ApiError(400, "搜索内容不能超过 100 字")
        val dates = listOf("start_date", "end_date").associateWith { key ->
            params[key]?.takeIf { it.isNotBlank() }?.let {
                runCatching { LocalDate.parse(it) }.getOrElse { throw ApiError(400, "日志日期格式应为 YYYY-MM-DD") }
            }
        }
        if (dates["start_date"] != null && dates["end_date"] != null && dates["start_date"]!! > dates["end_date"]!!) throw ApiError(400, "开始日期不能晚于结束日期")
        return audit.list(params + mapOf("q" to query, "operation" to operation, "start_date" to dates["start_date"]?.toString().orEmpty(),
            "end_date" to dates["end_date"]?.plusDays(1)?.toString().orEmpty()), page)
    }
}
