package com.cczxcsc

import com.cczxcsc.controller.ApiErrors
import java.io.IOException
import org.apache.catalina.connector.ClientAbortException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse

class ApiErrorsTest {
    @Test fun `图片下载断连后不再写JSON响应`() {
        val response = MockHttpServletResponse().apply { contentType = "image/png"; outputStream.write(byteArrayOf(1, 2, 3)); flushBuffer() }
        val result = ApiErrors().unexpected(ClientAbortException(IOException("Broken pipe")), response)
        assertNull(result)
        assertEquals("image/png", response.contentType)
        assertArrayEquals(byteArrayOf(1, 2, 3), response.contentAsByteArray)
    }
    @Test fun `未发送完成的资源响应异常恢复为JSON且保留真实500错误`() {
        val response = MockHttpServletResponse().apply { contentType = "image/png"; outputStream.write(byteArrayOf(1, 2, 3)) }
        val result = ApiErrors().unexpected(IllegalStateException("回归测试异常"), response)
        assertEquals(500, result!!.statusCode.value())
        assertEquals("application/json", response.contentType)
        assertEquals(0, response.contentAsByteArray.size)
        assertEquals("服务器内部错误", result.body!!["error"])
    }
}
