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
    private fun fixture(): Triple<Path, JdbcTemplate, AuthService> {
        val root = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val source = SQLiteDataSource().apply { url = "jdbc:sqlite:${root.resolve("test.db")}" }
        val jdbc = JdbcTemplate(source); val tx = TransactionTemplate(DataSourceTransactionManager(source))
        DatabaseInitializer(jdbc, tx, root.toString()).afterPropertiesSet()
        return Triple(root, jdbc, AuthService(jdbc, tx, PasswordVault(root.toString())))
    }
    @Test fun `新数据库没有默认账户也不会从旧共用密码自动创建账户`() {
        val (_, jdbc, auth) = fixture()
        auth.setSetting("admin_hash", Passwords.hash("旧密码Test123"))
        assertTrue(auth.listAccounts().isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Int::class.java))
        assertThrows(ApiError::class.java) { auth.login("cczx123456", "local", "新媒体中心") }
        assertThrows(ApiError::class.java) { auth.login("cczx123456", "local", "") }
        assertThrows(ApiError::class.java) { auth.currentPassword("新媒体中心") }
        assertTrue(auth.listAccounts().isEmpty())
    }
    @Test fun `多个账号独立改密重置且重复添加不覆盖密码`() {
        val (_, _, auth) = fixture()
        auth.addAccount("网络部"); auth.addAccount("科创社")
        assertEquals(setOf("网络部", "科创社"), auth.listAccounts().toSet())
        val first = auth.login("cczx123456", "local", "网络部")["token"].toString()
        val second = auth.login("cczx123456", "local", "科创社")["token"].toString()
        auth.changePassword("cczx123456", "NetworkChanged123", "网络部")
        assertThrows(ApiError::class.java) { auth.requireAdmin("Bearer $first") }
        assertEquals("科创社", auth.username("Bearer $second"))
        assertEquals("NetworkChanged123", auth.currentPassword("网络部"))
        assertThrows(ApiError::class.java) { auth.addAccount("网络部") }
        assertThrows(ApiError::class.java) { auth.addAccount("坏\n账号") }
        assertThrows(ApiError::class.java) { auth.resetPassword("不存在") }
        auth.resetPassword("网络部")
        assertEquals("cczx123456", auth.currentPassword("网络部"))
        assertEquals("科创社", auth.username("Bearer $second"))
    }
    @Test fun `默认密码改密和重置均不改写手动文档`() {
        val (root, jdbc, auth) = fixture()
        val doc = root.resolve("PROJECT.md")
        val original = "# 手动项目说明\n新账号默认密码：cczx123456\n"
        Files.writeString(doc, original)
        auth.addAccount("测试管理员")
        val token = auth.login("cczx123456", "local", "测试管理员")["token"].toString()
        auth.changePassword("cczx123456", "ChangedAdmin456", "测试管理员")
        assertThrows(ApiError::class.java) { auth.requireAdmin("Bearer $token") }
        assertEquals("ChangedAdmin456", auth.currentPassword("测试管理员"))
        assertFalse(jdbc.queryForObject("SELECT password_encrypted FROM accounts WHERE username='测试管理员'", String::class.java).orEmpty().contains("ChangedAdmin456"))
        auth.resetPassword("测试管理员")
        assertEquals("cczx123456", auth.currentPassword("测试管理员"))
        assertEquals(original, Files.readString(doc))
        Files.delete(doc)
        auth.changePassword("cczx123456", "AnotherAdmin123", "测试管理员")
        assertFalse(Files.exists(doc))
    }
    @Test fun `旧账户只有哈希时仍可登录但不返回未知密码`() {
        val (_, jdbc, auth) = fixture()
        jdbc.update("INSERT INTO accounts(username,password_hash,password_encrypted,initial_password,created_at) VALUES(?,?,?,0,?)", "旧账户", Passwords.hash("LegacyAdmin123"), "", nowIso())
        assertThrows(IllegalStateException::class.java) { auth.currentPassword("旧账户") }
        assertNotNull(auth.login("LegacyAdmin123", "local", "旧账户")["token"])
        auth.resetPassword("旧账户")
        assertEquals("cczx123456", auth.currentPassword("旧账户"))
    }
    @Test fun `查询密钥缺失不影响登录且不会创建替代密钥`() {
        val (root, _, auth) = fixture()
        auth.addAccount("测试管理员"); auth.changePassword("cczx123456", "CustomAdmin123", "测试管理员")
        Files.delete(root.resolve("admin-password.key"))
        assertNotNull(auth.login("CustomAdmin123", "local", "测试管理员")["token"])
        assertThrows(IllegalStateException::class.java) { auth.currentPassword("测试管理员") }
        assertFalse(Files.exists(root.resolve("admin-password.key")))
        auth.resetPassword("测试管理员")
        assertEquals("cczx123456", auth.currentPassword("测试管理员"))
    }
}
