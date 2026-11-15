package com.cczxcsc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ProjectDocumentTest {
    @Test fun `默认密码改密和重置均不改写手动文档`() {
        val root = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val data = Files.createDirectories(root.resolve("data"))
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${data.resolve("test.db")}" }
        val jdbc = JdbcTemplate(source); val tx = TransactionTemplate(DataSourceTransactionManager(source))
        DatabaseInitializer(jdbc, tx, data.toString()).afterPropertiesSet()
        val doc = root.resolve("PROJECT.md")
        val original = "# 手动维护的项目说明\n默认管理员密码：admin\n此处保留用户修改。\n"
        Files.writeString(doc, original)
        val auth = AuthService(jdbc, tx, "", PasswordVault(data.toString()))
        auth.initialize()
        assertEquals("admin", auth.currentPassword())
        assertEquals(original, Files.readString(doc))
        assertFalse(Files.exists(data.resolve("管理员初始密码.txt")))
        val session = auth.login("admin", "local")["token"].toString()
        auth.changePassword("admin", "ChangedAdmin456")
        assertThrows(ApiError::class.java) { auth.requireAdmin("Bearer $session") }
        assertThrows(ApiError::class.java) { auth.login("admin", "local") }
        assertEquals("ChangedAdmin456", auth.currentPassword())
        assertFalse(auth.setting("admin_password_encrypted").orEmpty().contains("ChangedAdmin456"))
        val restarted = AuthService(jdbc, tx, "DifferentConfig123", PasswordVault(data.toString()))
        restarted.initialize()
        assertEquals("ChangedAdmin456", restarted.currentPassword())
        assertEquals(original, Files.readString(doc))
        val newSession = auth.login("ChangedAdmin456", "local")["token"].toString()
        auth.resetPassword()
        assertThrows(ApiError::class.java) { auth.requireAdmin("Bearer $newSession") }
        assertEquals("admin", auth.currentPassword())
        assertEquals(original, Files.readString(doc))
        Files.delete(doc)
        auth.changePassword("admin", "AnotherAdmin123")
        auth.initialize()
        assertFalse(Files.exists(doc))
        auth.changePassword("AnotherAdmin123", "admin")
        assertEquals("admin", auth.currentPassword())
    }

    @Test fun `旧密码仅有哈希时不返回错误默认值且允许恢复默认`() {
        val root = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${root.resolve("test.db")}" }
        val jdbc = JdbcTemplate(source); val tx = TransactionTemplate(DataSourceTransactionManager(source))
        DatabaseInitializer(jdbc, tx, root.toString()).afterPropertiesSet()
        val auth = AuthService(jdbc, tx, "", PasswordVault(root.toString()))
        auth.setSetting("admin_hash", Passwords.hash("LegacyAdmin123"))
        auth.initialize()
        assertThrows(IllegalStateException::class.java) { auth.currentPassword() }
        assertNotNull(auth.login("LegacyAdmin123", "local")["token"])
        auth.resetPassword()
        assertEquals("admin", auth.currentPassword())
        Files.delete(root.resolve("admin-password.key"))
        assertEquals("admin", auth.currentPassword())
        assertFalse(Files.exists(root.resolve("admin-password.key")))
    }

    @Test fun `查询密钥缺失不影响登录且拒绝返回旧记录`() {
        val root = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${root.resolve("test.db")}" }
        val jdbc = JdbcTemplate(source); val tx = TransactionTemplate(DataSourceTransactionManager(source))
        DatabaseInitializer(jdbc, tx, root.toString()).afterPropertiesSet()
        val auth = AuthService(jdbc, tx, "", PasswordVault(root.toString()))
        auth.initialize(); auth.changePassword("admin", "CustomAdmin123")
        Files.delete(root.resolve("admin-password.key"))
        assertNotNull(auth.login("CustomAdmin123", "local")["token"])
        assertThrows(IllegalStateException::class.java) { auth.currentPassword() }
        assertFalse(Files.exists(root.resolve("admin-password.key")))
        auth.resetPassword()
        assertEquals("admin", auth.currentPassword())
    }
}
