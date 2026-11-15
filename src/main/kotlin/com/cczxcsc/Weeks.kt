package com.cczxcsc

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

data class Period(val key: String, val label: String, val startDate: String, val endDate: String)

object Weeks {
    fun key(date: LocalDate): String = "${date.get(WeekFields.ISO.weekBasedYear())}-W${date.get(WeekFields.ISO.weekOfWeekBasedYear()).toString().padStart(2, '0')}"
    fun at(date: LocalDate): Period {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val key = key(start)
        return Period(key, "${key.take(4)} 年第 ${key.takeLast(2).toInt()} 周", start.toString(), start.plusDays(6).toString())
    }
    fun current(): Period = at(LocalDate.now(schoolZone))
    fun available(): List<Period> { val today = LocalDate.now(schoolZone); return (0L..7L).map { at(today.plusWeeks(it)) } }
    fun parse(value: String): Period {
        val match = Regex("^(\\d{4})-W(\\d{2})$").matchEntire(value) ?: throw ApiError(400, "投稿周次格式不正确")
        val year = match.groupValues[1].toInt()
        val week = match.groupValues[2].toInt()
        if (year !in 2000..2100 || week !in 1..53) throw ApiError(400, "投稿周次不正确")
        val result = at(LocalDate.of(year, 1, 4).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks((week - 1).toLong()))
        if (result.key != value) throw ApiError(400, "这个年份没有所选周次")
        return result
    }
}
