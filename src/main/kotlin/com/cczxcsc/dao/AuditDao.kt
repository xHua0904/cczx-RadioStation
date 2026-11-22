package com.cczxcsc.dao

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class AuditDao(private val jdbc: JdbcTemplate) {
    companion object {
        val operationActions = mapOf(
            "review" to listOf("审核通过"), "schedule" to listOf("安排播出日程", "移出日程"), "reject" to listOf("退稿"),
            "reopen" to listOf("重新提交审批"), "played" to emptyList(), "note" to listOf("修改备注"), "delete" to listOf("删除稿件"),
            "announcement" to listOf("发布公告", "恢复发布", "撤回消息", "删除消息"),
            "account" to listOf("添加账号", "删除账号", "重置密码", "查询密码", "登录后台", "退出后台", "修改密码", "查询账号列表"),
            "export" to listOf("导出稿件"), "import" to listOf("导入旧稿件")
        )
    }
    fun insert(actor: String, action: String, type: String, id: String, label: String, time: String) {
        jdbc.update("INSERT INTO audit_log(username,action,target_type,target_id,target_label,created_at) VALUES(?,?,?,?,?,?)", actor, action, type, id, label, time)
    }
    fun list(params: Map<String, String>, page: Int): Map<String, Any> {
        // 仅隐藏投稿事件，历史数据继续保留；数量和分页使用相同过滤条件。
        val clauses = mutableListOf("action NOT IN ('在线投稿','手动登记')")
        val values = mutableListOf<Any>()
        val operation = params["operation"].orEmpty()
        if (operation == "played") { clauses += "action LIKE ?"; values += "切换播出状态：%" }
        else operationActions[operation]?.takeIf { it.isNotEmpty() }?.let { actions ->
            clauses += "action IN (${actions.joinToString(",") { "?" }})"
            values.addAll(actions)
        }
        for (key in listOf("target_type", "target_id", "username", "action")) {
            params[key]?.takeIf { it.isNotBlank() }?.let { clauses += "$key=?"; values += it }
        }
        params["q"]?.takeIf { it.isNotEmpty() }?.let {
            clauses += "(username LIKE ? OR action LIKE ? OR target_label LIKE ?)"
            repeat(3) { _ -> values += "%$it%" }
        }
        params["start_date"]?.takeIf { it.isNotEmpty() }?.let { clauses += "created_at>=?"; values += it }
        params["end_date"]?.takeIf { it.isNotEmpty() }?.let { clauses += "created_at<?"; values += it }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log$where", Long::class.java, *values.toTypedArray()) ?: 0
        val items = jdbc.queryForList("SELECT * FROM audit_log$where ORDER BY id DESC LIMIT 20 OFFSET ?", *(values + ((page - 1) * 20)).toTypedArray())
        return mapOf("items" to items, "total" to count, "page" to page, "pages" to ((count + 19) / 20).coerceAtLeast(1))
    }
}
