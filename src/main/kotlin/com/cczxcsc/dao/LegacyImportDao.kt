package com.cczxcsc.dao

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class LegacyImportDao(private val jdbc: JdbcTemplate, @Value("\${app.storage}") private val storage: String) {
    companion object {
        val columns = listOf("id", "code", "author", "class_name", "contact", "category", "title", "content", "status", "score", "broadcast_date", "note", "source", "created_at", "updated_at", "song_name", "artist", "song_url", "period", "broadcast_time")
    }
    fun insertSubmission(values: List<Any?>) {
        jdbc.update("INSERT INTO submissions(${columns.joinToString(",")}) VALUES(${columns.joinToString(",") { "?" }})", *values.toTypedArray())
    }
    fun setCounter(day: String, count: Int) {
        jdbc.update("INSERT INTO counters(day,n) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET n=excluded.n", day, count)
    }
    fun restoreCounter(day: String, count: Int) {
        jdbc.update("INSERT INTO counters(day,n) VALUES(?,?) ON CONFLICT(day) DO UPDATE SET n=CASE WHEN counters.n>excluded.n THEN counters.n ELSE excluded.n END", day, count)
    }
    fun setSetting(key: String, value: String) {
        jdbc.update("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)
    }
    fun finish() {
        jdbc.update("DELETE FROM sessions")
        if (storage == "postgres") jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('submissions','id'),COALESCE(MAX(id),1),MAX(id) IS NOT NULL) FROM submissions", Long::class.java)
    }
}
