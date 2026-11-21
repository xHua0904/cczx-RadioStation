package com.cczxcsc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ServerLogServiceTest {
    @Test fun `只读取文件尾部且保留中文行和返回数量上限`() {
        val root = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString()))
        val file = root.resolve("springboot.log")
        Files.writeString(file, "前面的旧日志".repeat(40000) + "\n" + (1..600).joinToString("\n") { "日志第${it}行" } + "\n")
        val result = ServerLogService(file.toString()).tail(20)
        @Suppress("UNCHECKED_CAST") val lines = result["lines"] as List<String>
        assertEquals(20, lines.size)
        assertEquals("日志第581行", lines.first())
        assertEquals("日志第600行", lines.last())
        assertThrows(ApiError::class.java) { ServerLogService(file.toString()).tail(501) }
    }
    @Test fun `日志尚未创建时返回空列表而不是服务器异常`() {
        val file = Path.of("target/test-data", UUID.randomUUID().toString(), "absent.log")
        val result = ServerLogService(file.toString()).tail(100)
        assertEquals(emptyList<String>(), result["lines"])
    }
}
