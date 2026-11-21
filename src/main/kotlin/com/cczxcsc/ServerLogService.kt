package com.cczxcsc

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Service
class ServerLogService(@Value("\${logging.file.name}") private val filename: String) {
    // 只读配置指定的应用日志，不接受客户端路径；最多读取末尾 256 KiB。
    fun tail(lines: Int): Map<String, Any> {
        if (lines !in 20..500) throw ApiError(400, "日志行数应为 20–500")
        val path = Path.of(filename)
        return try {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                val start = (size - 256 * 1024).coerceAtLeast(0)
                val buffer = ByteBuffer.allocate((size - start).toInt())
                channel.position(start)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
                buffer.flip()
                var text = Charsets.UTF_8.decode(buffer).toString()
                if (start > 0) text = text.substringAfter('\n', "")
                val items = text.lineSequence().map { it.removeSuffix("\r") }.toList().let {
                    if (it.lastOrNull() == "") it.dropLast(1) else it
                }.takeLast(lines)
                mapOf("file" to filename, "lines" to items, "updated_at" to Files.getLastModifiedTime(path).toInstant().toString(), "limit" to lines)
            }
        } catch (_: NoSuchFileException) {
            mapOf("file" to filename, "lines" to emptyList<String>(), "updated_at" to "", "limit" to lines)
        } catch (_: java.io.IOException) {
            throw ApiError(503, "无法读取应用日志，请检查日志目录权限")
        }
    }
}
