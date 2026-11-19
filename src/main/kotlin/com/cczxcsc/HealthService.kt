package com.cczxcsc

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class HealthService(private val jdbc: JdbcTemplate) {
    private var checkedAt = 0L
    private var healthy = false
    // 同一时间段的健康探测共享一次数据库检查，避免占满 SQLite 的连接池。
    @Synchronized fun check(): Boolean {
        val now = System.currentTimeMillis()
        if (now - checkedAt >= 5000) {
            healthy = runCatching { jdbc.queryForObject("SELECT 1", Int::class.java) == 1 }.getOrDefault(false)
            checkedAt = now
        }
        return healthy
    }
}
