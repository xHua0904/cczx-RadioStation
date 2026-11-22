package com.cczxcsc.dao

import com.cczxcsc.common.Weeks
import com.cczxcsc.common.nowIso
import com.cczxcsc.common.schoolZone
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
@ConditionalOnProperty(name = ["app.storage"], havingValue = "sqlite", matchIfMissing = true)
class DatabaseInitializer(
    private val jdbc: JdbcTemplate, private val transactions: TransactionTemplate,
    @Value("\${app.data-dir}") private val dataDir: String
) : InitializingBean {
    override fun afterPropertiesSet() {
        val oldTable = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='submissions'", Int::class.java) == 1
        val columns = if (oldTable) jdbc.queryForList("PRAGMA table_info(submissions)").map { it["name"].toString() }.toSet() else emptySet()
        val addedColumns = listOf("song_name", "artist", "song_url", "period", "broadcast_time", "reviewed_by", "scheduled_by", "updated_by")
        if (oldTable && addedColumns.any { it !in columns }) {
            // VACUUM INTO 会包含 WAL 中已提交的数据，升级前留下完整备份。
            Files.createDirectories(Path.of(dataDir))
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
            val backup = Path.of(dataDir, "broadcast-before-kotlin-$stamp.db").toAbsolutePath().toString()
            jdbc.execute(ConnectionCallback<Unit> { connection ->
                connection.prepareStatement("VACUUM INTO ?").use { it.setString(1, backup); it.execute() }
                Unit
            })
        }
        jdbc.execute("PRAGMA journal_mode=WAL")
        transactions.executeWithoutResult {
            jdbc.execute(ConnectionCallback<Unit> { connection -> ScriptUtils.executeSqlScript(connection, ClassPathResource("db/sqlite/V1__initial_schema.sql")) })
            for (name in addedColumns) {
                if (name !in columns) jdbc.execute("ALTER TABLE submissions ADD COLUMN $name TEXT NOT NULL DEFAULT ''")
            }
            if (oldTable && listOf("song_name", "artist", "song_url").any { it !in columns }) jdbc.update("DELETE FROM sessions")
            if ("period" !in columns) {
                jdbc.queryForList("SELECT id,created_at FROM submissions").forEach { row ->
                    val date = runCatching { java.time.LocalDate.parse(row["created_at"].toString().take(10)) }.getOrDefault(java.time.LocalDate.now(schoolZone))
                    jdbc.update("UPDATE submissions SET period=? WHERE id=?", Weeks.key(date), row["id"])
                }
            }
            jdbc.execute(ConnectionCallback<Unit> { connection -> ScriptUtils.executeSqlScript(connection, ClassPathResource("db/sqlite/V3__weekly_submissions_and_announcements.sql")) })
            jdbc.execute(ConnectionCallback<Unit> { connection -> ScriptUtils.executeSqlScript(connection, ClassPathResource("db/sqlite/V4__accounts_and_audit.sql")) })
            val sessionColumns = jdbc.queryForList("PRAGMA table_info(sessions)").map { it["name"].toString() }
            if ("username" !in sessionColumns) {
                jdbc.execute("ALTER TABLE sessions ADD COLUMN username TEXT NOT NULL DEFAULT ''")
                jdbc.update("DELETE FROM sessions")
            }
            val messageColumns = jdbc.queryForList("PRAGMA table_info(announcements)").map { it["name"].toString() }
            for (name in listOf("updated_by", "updated_at")) {
                if (name !in messageColumns) jdbc.execute("ALTER TABLE announcements ADD COLUMN $name TEXT NOT NULL DEFAULT ''")
            }
            jdbc.update("INSERT OR IGNORE INTO schema_versions(version, applied_at) VALUES(1, ?)", nowIso())
            jdbc.update("INSERT OR IGNORE INTO schema_versions(version, applied_at) VALUES(2, ?)", nowIso())
            jdbc.update("INSERT OR IGNORE INTO schema_versions(version, applied_at) VALUES(3, ?)", nowIso())
            jdbc.update("INSERT OR IGNORE INTO schema_versions(version, applied_at) VALUES(4, ?)", nowIso())
        }
    }
}
