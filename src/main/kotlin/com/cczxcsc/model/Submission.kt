package com.cczxcsc.model

data class SubmissionInput(
    val author: String = "", val className: String = "", val contact: String = "",
    val category: String = "", val title: String = "", val content: String = "",
    val songName: String = "", val artist: String = "", val songUrl: String = "",
    val status: String = "待审核", val note: String = "", val period: String = "",
    val message: String = "", val reason: String = ""
)

data class Submission(
    val id: Long, val code: String, val author: String, val className: String,
    val contact: String, val category: String, val title: String, val content: String,
    val status: String, val broadcastDate: String, val note: String,
    val source: String, val createdAt: String, val updatedAt: String,
    val songName: String, val artist: String, val songUrl: String,
    val period: String = "", val broadcastTime: String = "",
    val reviewedBy: String = "", val scheduledBy: String = "", val updatedBy: String = "",
    val message: String = "", val reason: String = "", val legacyContent: String = ""
)
