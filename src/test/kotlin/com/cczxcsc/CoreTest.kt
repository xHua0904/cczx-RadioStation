package com.cczxcsc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreTest {
    @Test fun `旧 Python 密码哈希支持中文密码`() {
        val old = "pbkdf2_sha256\$120000\$00112233445566778899aabbccddeeff\$bf4ddf9dd8a39d8d3b946b11bcdd2ebca0065a618088d41f35a697fccc213e02"
        assertTrue(Passwords.verify("校园Test123", old))
        assertFalse(Passwords.verify("错误密码", old))
        assertFalse(Passwords.verify("校园Test123", "pbkdf2_sha256\$999999999\$bad\$bad"))
    }
    @Test fun `稿件不能为空且最多一百字`() {
        val input = SubmissionInput(author = "测试同学", className = "高一一班", content = "稿")
        assertEquals("其他", Validation.submission(input).category)
        assertEquals(100, Validation.submission(input.copy(content = "稿".repeat(100))).content.length)
        assertThrows(ApiError::class.java) { Validation.submission(input.copy(content = " \n ")) }
        assertThrows(ApiError::class.java) { Validation.submission(input.copy(content = "稿".repeat(101))) }
        assertThrows(ApiError::class.java) { Validation.date("2026-02-30") }
    }
    @Test fun `自然周在跨年时采用 ISO 周年份`() {
        assertEquals("2020-W53", Weeks.key(java.time.LocalDate.of(2021, 1, 1)))
        assertEquals("2020-12-28", Weeks.parse("2020-W53").startDate)
        assertThrows(ApiError::class.java) { Weeks.parse("2021-W53") }
        assertEquals(8, Weeks.available().size)
    }
}
