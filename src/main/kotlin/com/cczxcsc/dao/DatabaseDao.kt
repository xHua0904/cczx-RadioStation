package com.cczxcsc.dao

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class DatabaseDao(private val jdbc: JdbcTemplate) {
    fun latencyMs(): Double {
        val started = System.nanoTime()
        jdbc.queryForObject("SELECT 1", Int::class.java)
        return (System.nanoTime() - started) / 1_000_000.0
    }
}
