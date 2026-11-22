package com.cczxcsc.common

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val schoolZone: ZoneId = ZoneId.of("Asia/Shanghai")
val categories = listOf("校园新闻", "美文朗读", "点歌祝福", "失物招领", "活动通知", "人物访谈", "英语角", "其他")
val statuses = listOf("待审核", "已通过", "已播出", "已退稿")
val adoptedStatuses = setOf("已通过", "已播出")
val schoolClasses = (1..11).map { "高一${it}班" } + (1..9).map { "高二${it}班" }
fun nowIso(): String = OffsetDateTime.now(schoolZone).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
