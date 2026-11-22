package com.cczxcsc.common

import com.cczxcsc.model.SongRequestNotes
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

// 复用已有正文列保存两项独立信息，带版本标记；旧正文保持原样，不需要数据库迁移。
object SongRequestContent {
    private const val FORMAT = "cczx-song-request-v1"
    private val json = jacksonObjectMapper()

    fun encode(message: String, reason: String): String = json.writeValueAsString(
        mapOf("_format" to FORMAT, "message" to message, "reason" to reason)
    )

    fun decode(content: String): SongRequestNotes? = runCatching {
        val node = json.readTree(content)
        if (!node.isObject || node.path("_format").asText() != FORMAT || !node.path("message").isTextual || !node.path("reason").isTextual) null
        else SongRequestNotes(node.path("message").asText(), node.path("reason").asText())
    }.getOrNull()

    fun summary(song: String, artist: String, notes: SongRequestNotes): String = buildList {
        add("点播《$song》 · $artist")
        if (notes.message.isNotBlank()) add("寄语：${notes.message}")
        if (notes.reason.isNotBlank()) add("点播理由：${notes.reason}")
    }.joinToString("\n")
}
