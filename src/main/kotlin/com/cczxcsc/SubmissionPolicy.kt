package com.cczxcsc

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress

// 每个来源独立计数；并发检查与计数在同一锁内完成，容量满时保留已有窗口。
class SubmissionRateLimiter(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }, private val maxSources: Int = 10_000) {
    private data class Window(var count: Int, val until: Long)
    private val windows = mutableMapOf<String, Window>()
    init { require(maxSources > 0) }
    @Synchronized fun retryAfter(source: String, limit: Int): Int {
        require(limit > 0)
        val now = clock()
        val current = windows[source]?.takeIf { it.until > now }
        if (current != null) {
            if (current.count >= limit) return ((current.until - now + 999) / 1000).toInt().coerceAtLeast(1)
            current.count++
            return 0
        }
        if (windows.size >= maxSources) windows.entries.removeIf { it.value.until <= now }
        if (source !in windows && windows.size >= maxSources) {
            return ((windows.values.minOf { it.until } - now + 999) / 1000).toInt().coerceAtLeast(1)
        }
        windows[source] = Window(1, now + 60_000)
        return 0
    }
}

// 仅当连接来源属于明确配置的 HTTP 代理时读取转发头，不默认信任客户端填写的 IP。
class SubmissionSource(trustedProxies: String) {
    private data class Network(val address: ByteArray, val prefix: Int)
    private val networks = trustedProxies.split(',').filter { it.isNotBlank() }.map { value ->
        val parts = value.trim().split('/')
        require(parts.size <= 2) { "可信代理格式不正确" }
        val address = numeric(parts[0]) ?: throw IllegalArgumentException("可信代理必须使用 IP 或 CIDR")
        val prefix = parts.getOrNull(1)?.toIntOrNull() ?: if (parts.size == 1) address.size * 8 else -1
        require(prefix in 0..address.size * 8) { "可信代理 CIDR 前缀不正确" }
        Network(address, prefix)
    }
    private fun numeric(value: String): ByteArray? {
        if (value.length > 64 || !Regex("[0-9a-fA-F:.]+").matches(value)) return null
        if (':' !in value && (!Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(value) || value.split('.').any { it.toInt() > 255 })) return null
        return runCatching { InetAddress.getByName(value).address }.getOrNull()
    }
    private fun trusted(value: String): Boolean {
        val address = numeric(value) ?: return false
        return networks.any { network -> address.size == network.address.size && (0 until network.prefix).all { bit ->
            val mask = 1 shl (7 - bit % 8)
            (address[bit / 8].toInt() and mask) == (network.address[bit / 8].toInt() and mask)
        } }
    }
    fun address(request: HttpServletRequest): String {
        var client = request.remoteAddr
        if (trusted(client)) {
            val header = request.getHeader("X-Forwarded-For").orEmpty()
            val hops = header.split(',').map(String::trim)
            if (header.length in 1..1024 && hops.size <= 16 && hops.all { numeric(it) != null }) {
                for (hop in hops.asReversed()) { if (!trusted(client)) break; client = hop }
            }
        }
        return numeric(client)?.let { InetAddress.getByAddress(it).hostAddress } ?: client
    }
}

@Component
class SubmissionPolicy(@Value("\${app.submissions-per-minute:20}") private val limit: Int,
    @Value("\${app.trusted-proxies:}") trustedProxies: String) {
    private val limiter = SubmissionRateLimiter()
    private val source = SubmissionSource(trustedProxies)
    init { require(limit > 0) { "SUBMISSIONS_PER_MINUTE 必须大于 0" } }
    fun retryAfter(request: HttpServletRequest): Int = limiter.retryAfter(source.address(request), limit)
}
