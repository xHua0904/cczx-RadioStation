package com.cczxcsc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class SecurityPolicyTest {
    @Test fun `限流到期后恢复且容量满时不能挤掉其他来源`() {
        var now = 1000L
        val limiter = WindowLimiter({ now }, 2)
        assertTrue(limiter.allow("a", 2, 1000)); assertTrue(limiter.allow("a", 2, 1000))
        assertFalse(limiter.allow("a", 2, 1000))
        assertTrue(limiter.allow("b", 1, 1000)); assertFalse(limiter.allow("c", 1, 1000))
        assertFalse(limiter.allow("a", 2, 1000))
        now = 2000
        assertTrue(limiter.allow("c", 1, 1000)); assertTrue(limiter.allow("a", 2, 1000))
    }

    @Test fun `代理头只信明确代理且从右侧还原来源`() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.9"
            addHeader("X-Forwarded-For", "192.0.2.1")
            addHeader("X-Forwarded-Proto", "https")
        }
        assertEquals(ClientInfo("203.0.113.9", false), ClientIdentity("").resolve(request))
        request.remoteAddr = "10.0.0.2"
        request.removeHeader("X-Forwarded-For")
        request.addHeader("X-Forwarded-For", "192.0.2.1, 203.0.113.8, 10.0.0.3")
        assertEquals(ClientInfo("203.0.113.8", true), ClientIdentity("10.0.0.0/24").resolve(request))
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "attacker.example")
        assertEquals("10.0.0.2", ClientIdentity("10.0.0.2/32").resolve(request).ip)
        request.remoteAddr = "2001:db8::2"
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "2001:db9::1")
        assertEquals("2001:db9::1", ClientIdentity("2001:db8::/32").resolve(request).ip)
        assertThrows(IllegalArgumentException::class.java) { ClientIdentity("proxy.example") }
    }

    @Test fun `公网模式拒绝伪造HTTPS并保留内部健康探测`() {
        val filter = RequestLimits(ObjectMapper(), ClientIdentity(""), true, 20)
        fun response(path: String): MockHttpServletResponse {
            val request = MockHttpServletRequest("GET", path).apply { servletPath = path; remoteAddr = "203.0.113.1"; addHeader("X-Forwarded-Proto", "https") }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response) { _, _ -> response.status = 204 }
            return response
        }
        assertEquals(426, response("/").status)
        assertEquals(204, response("/api/health").status)
        assertNull(response("/").getHeader("Strict-Transport-Security"))
    }

    @Test fun `旧密钥迁移能解密且冲突时不覆盖任何密钥`() {
        val root = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        val data = root.resolve("data"); val secrets = root.resolve("secrets")
        val legacy = PasswordVault(data.toString())
        val encrypted = legacy.encrypt("迁移测试123456")
        val bytes = Files.readAllBytes(data.resolve("admin-password.key"))
        val migrated = PasswordVault(data.toString(), secrets.toString())
        migrated.prepare()
        assertFalse(Files.exists(data.resolve("admin-password.key")))
        assertArrayEquals(bytes, Files.readAllBytes(secrets.resolve("admin-password.key")))
        assertEquals("迁移测试123456", migrated.decrypt(encrypted))
        val conflicting = ByteArray(32) { 7 }
        Files.write(data.resolve("admin-password.key"), conflicting)
        assertThrows(IllegalArgumentException::class.java) { migrated.prepare() }
        assertArrayEquals(conflicting, Files.readAllBytes(data.resolve("admin-password.key")))
        assertArrayEquals(bytes, Files.readAllBytes(secrets.resolve("admin-password.key")))
    }

    @Test fun `健康检查共享数据库结果且新密码采用六十万轮`() {
        var calls = 0
        val jdbc = object : JdbcTemplate() {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>): T? {
                calls++; return 1 as T
            }
        }
        val health = HealthService(jdbc)
        repeat(20) { assertTrue(health.check()) }
        assertEquals(1, calls)
        var failures = 0
        val unavailable = HealthService(object : JdbcTemplate() {
            override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>): T? {
                failures++; throw IllegalStateException("数据库暂不可用")
            }
        })
        repeat(20) { assertFalse(unavailable.check()) }
        assertEquals(1, failures)
        assertTrue(Passwords.hash("SecureTest123").startsWith("pbkdf2_sha256\$600000\$"))
        assertFalse(Passwords.validAdminPassword("short123"))
        assertFalse(Passwords.validAdminPassword("abcdefghijk"))
        assertFalse(Passwords.validAdminPassword("Secure1234\n"))
    }
}
