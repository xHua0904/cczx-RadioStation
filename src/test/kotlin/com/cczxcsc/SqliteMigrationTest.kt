package com.cczxcsc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.transaction.support.TransactionTemplate
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class SqliteMigrationTest {
    @Test fun `旧库升级保留投稿和密码并生成可恢复备份`() {
        val directory = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${directory.resolve("legacy.db")}" }
        source.connection.use { ScriptUtils.executeSqlScript(it, ClassPathResource("db/sqlite/V1__initial_schema.sql")) }
        val jdbc = JdbcTemplate(source)
        jdbc.update("INSERT INTO submissions(code,author,class_name,category,title,content,created_at,updated_at) VALUES('GB261115001','测试同学','高一一班','其他','旧稿件','保留原有投稿内容','2026-11-15T10:00:00+08:00','2026-11-15T10:00:00+08:00')")
        jdbc.update("INSERT INTO settings(key,value) VALUES('admin_hash','legacy-password-hash')")
        jdbc.update("INSERT INTO sessions(token,created_at,expires_at) VALUES('old-session','2026-11-15','2027-01-01')")
        val initializer = DatabaseInitializer(jdbc, TransactionTemplate(DataSourceTransactionManager(source)), directory.toString())
        initializer.afterPropertiesSet(); initializer.afterPropertiesSet()
        assertEquals("保留原有投稿内容", jdbc.queryForObject("SELECT content FROM submissions", String::class.java))
        assertEquals("legacy-password-hash", jdbc.queryForObject("SELECT value FROM settings WHERE key='admin_hash'", String::class.java))
        assertEquals("", jdbc.queryForObject("SELECT song_name FROM submissions", String::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Int::class.java))
        Files.list(directory).use { paths -> assertEquals(1L, paths.filter { it.fileName.toString().startsWith("broadcast-before-kotlin-") }.count()) }
    }
}
