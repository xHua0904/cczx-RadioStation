package com.cczxcsc

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

// 操作人来自已验证的登录会话；与业务写入共用事务，失败操作不留下成功记录。
@Service
class AuditService(private val jdbc: JdbcTemplate) {
    fun record(actor: String, action: String, type: String, id: Any, label: String) {
        jdbc.update("INSERT INTO audit_log(username,action,target_type,target_id,target_label,created_at) VALUES(?,?,?,?,?,?)",
            actor, action, type, id.toString(), label, nowIso())
    }

    fun list(params: Map<String, String>): Map<String, Any> {
        val page = params["page"]?.toIntOrNull()?.coerceIn(1, 1_000_000) ?: 1
        val clauses = mutableListOf<String>()
        val values = mutableListOf<Any>()
        for (key in listOf("target_type", "target_id")) {
            params[key]?.takeIf { it.isNotBlank() }?.let { clauses += "$key=?"; values += it }
        }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log$where", Long::class.java, *values.toTypedArray()) ?: 0
        val items = jdbc.queryForList("SELECT * FROM audit_log$where ORDER BY id DESC LIMIT 20 OFFSET ?", *(values + ((page - 1) * 20)).toTypedArray())
        return mapOf("items" to items, "total" to count, "page" to page, "pages" to ((count + 19) / 20).coerceAtLeast(1))
    }
}
