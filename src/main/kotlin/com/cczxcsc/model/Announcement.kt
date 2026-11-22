package com.cczxcsc.model

data class AnnouncementInput(val title: String = "", val content: String = "")
data class Announcement(val id: Long, val title: String, val content: String,
    val publisher: String, val published: Boolean, val createdAt: String, val publishedAt: String,
    val updatedBy: String = "", val updatedAt: String = "")
