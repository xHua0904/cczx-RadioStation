package com.cczxcsc.service

import com.cczxcsc.common.nowIso
import com.cczxcsc.dao.DatabaseDao
import java.lang.management.ManagementFactory
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ServerInfoService(private val database: DatabaseDao, @Value("\${app.storage}") private val storage: String) {
    fun health(): Map<String, Any> {
        database.latencyMs()
        return mapOf("ok" to true, "time" to nowIso())
    }
    fun info(): Map<String, Any> {
        val latency = database.latencyMs()
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
