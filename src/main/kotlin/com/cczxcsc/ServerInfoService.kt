package com.cczxcsc

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.lang.management.ManagementFactory
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

@Service
class ServerInfoService(private val jdbc: JdbcTemplate, @Value("\${app.storage}") private val storage: String) {
    fun info(): Map<String, Any> {
        val started = System.nanoTime()
        jdbc.queryForObject("SELECT 1", Int::class.java)
        val latency = (System.nanoTime() - started) / 1_000_000.0
        // 从本机网卡读取地址，优先显示局域网 IPv4，无需访问外部服务。
        val addresses = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .sortedWith(compareByDescending<Inet4Address> { it.isSiteLocalAddress }.thenBy { it.hostAddress })
                .map { it.hostAddress }.distinct()
        }.getOrDefault(emptyList()).ifEmpty { listOf("127.0.0.1") }
        val runtime = Runtime.getRuntime()
        return mapOf("online" to true, "time" to nowIso(), "uptime_seconds" to ManagementFactory.getRuntimeMXBean().uptime / 1000,
            "java_version" to System.getProperty("java.version"), "storage" to if (storage == "postgres") "PostgreSQL" else "SQLite",
            "database_latency_ms" to latency, "server_ip" to addresses.first(), "server_ips" to addresses,
            "memory_used_mb" to (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
            "memory_max_mb" to runtime.maxMemory() / 1024 / 1024,
            "processors" to runtime.availableProcessors())
    }
}
