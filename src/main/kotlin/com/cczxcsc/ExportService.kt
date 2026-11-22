package com.cczxcsc

import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ExportFile(val bytes: ByteArray, val contentType: String, val filename: String)

@Service
class ExportService {
    fun export(items: List<Submission>, format: String): ExportFile {
        if (format !in setOf("csv", "xlsx")) throw ApiError(400, "导出格式应为 csv 或 xlsx")
        val headers = listOf("编号", "投稿时间", "投稿人", "班级/部门", "歌曲名称", "歌手", "简短寄语", "点播理由", "历史标题", "历史投稿正文", "联系方式", "状态", "播出日期", "播出时间", "审核备注", "来源", "审稿人", "排期人", "最近操作人")
        val rows: List<List<Any?>> = listOf(headers) + items.map {
            listOf(it.code, it.createdAt.replace('T', ' ').take(16), it.author, it.className, it.songName, it.artist, it.message, it.reason,
                if (it.legacyContent.isNotBlank()) it.title else "", it.legacyContent, it.contact, it.status, it.broadcastDate, it.broadcastTime, it.note, it.source, it.reviewedBy, it.scheduledBy, it.updatedBy)
        }
        val stamp = LocalDateTime.now(schoolZone).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        if (format == "csv") {
            val csv = "\uFEFF" + rows.joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { csvCell(it) } }
            return ExportFile(csv.toByteArray(Charsets.UTF_8), "text/csv;charset=UTF-8", "广播投稿明细_$stamp.csv")
        }
        val statistics = mutableListOf<List<Any?>>(listOf("指标", "数值"), listOf("投稿总数", items.size),
            listOf("已通过/已播出", items.count { it.status in adoptedStatuses }), listOf("已播出", items.count { it.status == "已播出" }),
            emptyList(), listOf("栏目", "投稿数"))
        items.groupingBy { it.category }.eachCount().toList().sortedByDescending { it.second }.forEach { statistics.add(listOf(it.first, it.second)) }
        val bytes = ByteArrayOutputStream()
        XSSFWorkbook().use { workbook ->
            val headerStyle = workbook.createCellStyle().apply {
                fillForegroundColor = IndexedColors.DARK_TEAL.index; fillPattern = FillPatternType.SOLID_FOREGROUND
                setFont(workbook.createFont().apply { bold = true; color = IndexedColors.WHITE.index })
            }
            val wrapStyle = workbook.createCellStyle().apply { wrapText = true }
            for ((name, data) in listOf("投稿明细" to rows, "统计" to statistics)) {
                val sheet = workbook.createSheet(name)
                data.forEachIndexed { rowIndex, values ->
                    val row = sheet.createRow(rowIndex)
                    values.forEachIndexed { column, value ->
                        val cell = row.createCell(column)
                        if (value is Number) cell.setCellValue(value.toDouble()) else cell.setCellValue(value?.toString().orEmpty())
                        cell.cellStyle = if (rowIndex == 0) headerStyle else wrapStyle
                    }
                }
                val columns = data.maxOfOrNull { it.size } ?: 1
                for (column in 0 until columns) sheet.setColumnWidth(column, (if (column in setOf(6, 7, 9, 14)) 45 else 18) * 256)
                sheet.createFreezePane(0, 1)
                if (data.size > 1) sheet.setAutoFilter(CellRangeAddress(0, data.lastIndex, 0, columns - 1))
            }
            workbook.write(bytes)
        }
        return ExportFile(bytes.toByteArray(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "广播投稿明细_$stamp.xlsx")
    }

    private fun csvCell(value: Any?): String {
        var text = value?.toString().orEmpty()
        // CSV 在 Excel 中打开时，不让用户输入被解释成公式。
        if (value !is Number && text.trimStart().firstOrNull() in setOf('=', '+', '-', '@')) text = "'$text"
        return "\"${text.replace("\"", "\"\"")}\""
    }
}
