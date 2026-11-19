package com.cczxcsc

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.InetAddress

// 固定时间窗内计数；条目有上限，拒绝新来源而不是挤掉尚未过期的限流记录。
class WindowLimiter(private val clock: () -> Long = System::currentTimeMillis, private val maxKeys: Int = 10_000) {
    private data class Bucket(var count: Int, val until: Long)
    private val buckets = mutableMapOf<String, Bucket>()
    @Synchronized fun allow(key: String, limit: Int, windowMillis: Long): Boolean {
        val now = clock()
        val existing = buckets[key]?.takeIf { it.until > now }
        if (existing != null) {
            if (existing.count >= limit) return false
            existing.count++
            return true
        }
        if (buckets.size >= maxKeys) buckets.entries.removeIf { it.value.until <= now }
        if (key !in buckets && buckets.size >= maxKeys) return false
        buckets[key] = Bucket(1, now + windowMillis)
        return true
    }
}

data class ClientInfo(val ip: String, val secure: Boolean)

@Component
class ClientIdentity(@Value("\${app.trusted-proxies:}") trustedProxies: String) {
    private data class Network(val address: ByteArray, val prefix: Int)
    private val networks = trustedProxies.split(',').filter { it.isNotBlank() }.map { item ->
        val parts = item.trim().split('/')
        require(parts.size <= 2) { "可信代理地址格式不正确" }
        val address = numericAddress(parts[0]) ?: throw IllegalArgumentException("可信代理必须使用 IP 或 CIDR")
        val bits = parts.getOrNull(1)?.toIntOrNull() ?: if (parts.size == 1) address.size * 8 else -1
        require(bits in 0..address.size * 8) { "可信代理 CIDR 前缀不正确" }
        Network(address, bits)
    }
    private fun numericAddress(value: String): ByteArray? {
        if (value.length > 64 || !Regex("[0-9a-fA-F:.]+").matches(value)) return null
        if (':' !in value && (!Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(value) || value.split('.').any { it.toInt() > 255 })) return null
        return runCatching { InetAddress.getByName(value).address }.getOrNull()
    }
    private fun trusted(ip: String): Boolean {
        val address = numericAddress(ip) ?: return false
        return networks.any { network ->
            if (address.size != network.address.size) false else (0 until network.prefix).all { bit ->
                val mask = 1 shl (7 - bit % 8)
                (address[bit / 8].toInt() and mask) == (network.address[bit / 8].toInt() and mask)
            }
        }
    }
    fun resolve(request: HttpServletRequest): ClientInfo {
        val peer = request.remoteAddr
        if (!trusted(peer)) return ClientInfo(peer, request.isSecure)
        val header = request.getHeader("X-Forwarded-For").orEmpty()
        val hops = header.split(',').map(String::trim)
        var client = peer
        // 从最近一跳向外验证，不能让客户端自己填写的左端地址覆盖真实来源。
        if (header.length in 1..1024 && hops.size <= 16 && hops.all { numericAddress(it) != null }) {
            for (hop in hops.asReversed()) { if (!trusted(client)) break; client = hop }
        }
        val secure = request.isSecure || request.getHeader("X-Forwarded-Proto")?.equals("https", ignoreCase = true) == true
        return ClientInfo(client, secure)
    }
}
