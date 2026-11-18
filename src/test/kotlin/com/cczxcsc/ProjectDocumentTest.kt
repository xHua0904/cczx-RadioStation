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
    @Test fun `多个账号独立改密重置且重复添加不覆盖密码`() {
        val root = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${root.resolve("test.db")}" }
        val jdbc = JdbcTemplate(source); val tx = TransactionTemplate(DataSourceTransactionManager(source))
        DatabaseInitializer(jdbc, tx, root.toString()).afterPropertiesSet()
        val auth = AuthService(jdbc, tx, "", PasswordVault(root.toString()))
        auth.initialize(); auth.addAccount("网络部"); auth.addAccount("科创社")
        assertEquals(listOf("新媒体中心", "科创社", "网络部").toSet(), auth.listAccounts().toSet())
        val first = auth.login("cczx123456", "local", "网络部")["token"].toString()
        val second = auth.login("cczx123456", "local", "科创社")["token"].toString()
        assertEquals("网络部", auth.username("Bearer $first"))
        auth.changePassword("cczx123456", "NetworkChanged123", "网络部")
        assertThrows(ApiError::class.java) { auth.requireAdmin("Bearer $first") }
        assertEquals("科创社", auth.username("Bearer $second"))
        assertEquals("NetworkChanged123", auth.currentPassword("网络部"))
        assertThrows(ApiError::class.java) { auth.addAccount("网络部") }
        assertEquals("NetworkChanged123", auth.currentPassword("网络部"))
        assertThrows(ApiError::class.java) { auth.addAccount("\n") }
        assertThrows(ApiError::class.java) { auth.addAccount("坏\n账号") }
        assertThrows(ApiError::class.java) { auth.resetPassword("不存在") }
        auth.resetPassword("网络部")
        assertEquals("cczx123456", auth.currentPassword("网络部"))
        assertEquals("科创社", auth.username("Bearer $second"))
        assertNotNull(auth.login("cczx123456", "local", "网络部")["token"])
    }

    @Test fun `旧默认密码升级且旧会话失效升级可重复执行`() {
        val root = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${root.resolve("test.db")}" }
        val jdbc = JdbcTemplate(source); val tx = TransactionTemplate(DataSourceTransactionManager(source))
        DatabaseInitializer(jdbc, tx, root.toString()).afterPropertiesSet()
        val auth = AuthService(jdbc, tx, "", PasswordVault(root.toString()))
        auth.setSetting("admin_hash", Passwords.hash("admin"))
        auth.initialize(); auth.initialize()
        assertEquals(listOf("新媒体中心"), auth.listAccounts())
        assertEquals("cczx123456", auth.currentPassword())
        assertNull(auth.setting("admin_hash"))
        assertThrows(ApiError::class.java) { auth.login("admin", "local") }
        assertNotNull(auth.login("cczx123456", "local")["token"])
    }

    @Test fun `默认密码改密和重置均不改写手动文档`() {
        val root = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val data = Files.createDirectories(root.resolve("data"))
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${data.resolve("test.db")}" }
        val jdbc = JdbcTemplate(source); val tx = TransactionTemplate(DataSourceTransactionManager(source))
        DatabaseInitializer(jdbc, tx, data.toString()).afterPropertiesSet()
        val doc = root.resolve("PROJECT.md")
        val original = "# 手动维护的项目说明\n默认管理员密码：cczx123456\n此处保留用户修改。\n"
        Files.writeString(doc, original)
        val auth = AuthService(jdbc, tx, "", PasswordVault(data.toString()))
        auth.initialize()
        assertEquals("cczx123456", auth.currentPassword())
        assertEquals(original, Files.readString(doc))
        assertFalse(Files.exists(data.resolve("管理员初始密码.txt")))
        val session = auth.login("cczx123456", "local")["token"].toString()
        auth.changePassword("cczx123456", "ChangedAdmin456")
        assertThrows(ApiError::class.java) { auth.requireAdmin("Bearer $session") }
        assertThrows(ApiError::class.java) { auth.login("cczx123456", "local") }
        assertEquals("ChangedAdmin456", auth.currentPassword())
        assertFalse(jdbc.queryForObject("SELECT password_encrypted FROM accounts WHERE username='新媒体中心'", String::class.java).orEmpty().contains("ChangedAdmin456"))
        val restarted = AuthService(jdbc, tx, "DifferentConfig123", PasswordVault(data.toString()))
        restarted.initialize()
        assertEquals("ChangedAdmin456", restarted.currentPassword())
        assertEquals(original, Files.readString(doc))
        val newSession = auth.login("ChangedAdmin456", "local")["token"].toString()
        auth.resetPassword()
        assertThrows(ApiError::class.java) { auth.requireAdmin("Bearer $newSession") }
        assertEquals("cczx123456", auth.currentPassword())
        assertEquals(original, Files.readString(doc))
        Files.delete(doc)
        auth.changePassword("cczx123456", "AnotherAdmin123")
        auth.initialize()
        assertFalse(Files.exists(doc))
        auth.changePassword("AnotherAdmin123", "cczx123456")
        assertEquals("cczx123456", auth.currentPassword())
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
        assertEquals("cczx123456", auth.currentPassword())
        Files.delete(root.resolve("admin-password.key"))
        assertEquals("cczx123456", auth.currentPassword())
        assertFalse(Files.exists(root.resolve("admin-password.key")))
    }

    @Test fun `查询密钥缺失不影响登录且拒绝返回旧记录`() {
        val root = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${root.resolve("test.db")}" }
        val jdbc = JdbcTemplate(source); val tx = TransactionTemplate(DataSourceTransactionManager(source))
        DatabaseInitializer(jdbc, tx, root.toString()).afterPropertiesSet()
        val auth = AuthService(jdbc, tx, "", PasswordVault(root.toString()))
        auth.initialize(); auth.changePassword("cczx123456", "CustomAdmin123")
        Files.delete(root.resolve("admin-password.key"))
        assertNotNull(auth.login("CustomAdmin123", "local")["token"])
        assertThrows(IllegalStateException::class.java) { auth.currentPassword() }
        assertFalse(Files.exists(root.resolve("admin-password.key")))
        auth.resetPassword()
        assertEquals("cczx123456", auth.currentPassword())
    }
}
