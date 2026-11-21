package com.cczxcsc

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

// 操作人来自已验证的登录会话；与业务写入共用事务，失败操作不留下成功记录。
@Service
class AuditService(private val jdbc: JdbcTemplate) {
    companion object { val commandActor: String get() = "命令行：${System.getProperty("user.name", "未知用户")}" }
    fun record(actor: String, action: String, type: String, id: Any, label: String) {
        jdbc.update("INSERT INTO audit_log(username,action,target_type,target_id,target_label,created_at) VALUES(?,?,?,?,?,?)",
            actor, action, type, id.toString(), label, nowIso())
    }

    fun list(params: Map<String, String>): Map<String, Any> {
        val page = params["page"]?.toIntOrNull()?.coerceIn(1, 1_000_000) ?: 1
        val clauses = mutableListOf<String>()
        val values = mutableListOf<Any>()
        for (key in listOf("target_type", "target_id", "username", "action")) {
            params[key]?.takeIf { it.isNotBlank() }?.let { clauses += "$key=?"; values += it }
        }
        params["q"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.length > 100) throw ApiError(400, "搜索内容不能超过 100 字")
            clauses += "(username LIKE ? OR action LIKE ? OR target_label LIKE ?)"
            repeat(3) { _ -> values += "%$it%" }
        }
        val dates = listOf("start_date", "end_date").associateWith { key ->
            params[key]?.takeIf { it.isNotBlank() }?.let {
                runCatching { LocalDate.parse(it) }.getOrElse { throw ApiError(400, "日志日期格式应为 YYYY-MM-DD") }
            }
        }
        if (dates["start_date"] != null && dates["end_date"] != null && dates["start_date"]!! > dates["end_date"]!!) throw ApiError(400, "开始日期不能晚于结束日期")
        dates["start_date"]?.let { clauses += "created_at>=?"; values += it.toString() }
        dates["end_date"]?.let { clauses += "created_at<?"; values += it.plusDays(1).toString() }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log$where", Long::class.java, *values.toTypedArray()) ?: 0
        val items = jdbc.queryForList("SELECT * FROM audit_log$where ORDER BY id DESC LIMIT 20 OFFSET ?", *(values + ((page - 1) * 20)).toTypedArray())
        return mapOf("items" to items, "total" to count, "page" to page, "pages" to ((count + 19) / 20).coerceAtLeast(1))
    }
}
