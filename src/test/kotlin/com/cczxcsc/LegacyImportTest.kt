package com.cczxcsc

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LegacyImportTest {
    @Test fun `导入保留编号密码并恢复当天计数`() {
        val directory = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${directory.resolve("import.db")}" }
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(DataSourceTransactionManager(source))
        DatabaseInitializer(jdbc, transactions, directory.toString()).afterPropertiesSet()
        val repo = SubmissionRepository(jdbc)
        val json = jacksonObjectMapper()
        val hash = Passwords.hash("旧密码Test123")
        val payload = mapOf("format" to "radio-submissions-legacy-v1", "submissions" to listOf(mapOf(
            "id" to 42, "code" to "GB261115042", "author" to "测试同学", "class_name" to "高一一班",
            "category" to "其他", "title" to "旧稿件", "content" to "保留旧稿件内容", "status" to "已通过",
            "created_at" to "2026-11-15T10:00:00+08:00", "updated_at" to "2026-11-15T10:00:00+08:00")),
            "settings" to listOf(mapOf("key" to "admin_hash", "value" to hash)), "counters" to emptyList<Any>())
        val file = directory.resolve("legacy.json")
        Files.writeString(file, json.writeValueAsString(payload))
        val auth = AuthService(jdbc, transactions, "", PasswordVault(directory.toString()))
        val importer = LegacyImportService(repo, json, auth, "sqlite")
        transactions.executeWithoutResult { importer.importFile(file) }
        assertEquals("保留旧稿件内容", repo.byId(42)?.content)
        assertEquals("GB261115043", transactions.execute { repo.nextCode("2026-11-15") })
        assertTrue(Passwords.verify("旧密码Test123", auth.setting("admin_hash").orEmpty()))
        assertThrows(IllegalArgumentException::class.java) { transactions.executeWithoutResult { importer.importFile(file) } }
        assertEquals(1L, repo.count())
    }
}
