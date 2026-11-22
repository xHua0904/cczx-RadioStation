package com.cczxcsc

import com.cczxcsc.service.AuditService
import com.cczxcsc.service.AuthService
import com.cczxcsc.service.DemoService
import com.cczxcsc.service.LegacyImportService
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order

private val managementOptions = setOf("import-legacy", "reset-password", "show-password", "demo-data", "add-account", "delete-account", "list-accounts")

@SpringBootApplication
class Application {
    @Bean
    @Order(1)
    fun startup(auth: AuthService, imports: LegacyImportService, demo: DemoService, audit: AuditService) = ApplicationRunner { args: ApplicationArguments ->
        require(managementOptions.count(args::containsOption) <= 1) { "一次只能指定一个管理命令" }
        require(!args.containsOption("account") || args.containsOption("show-password") || args.containsOption("reset-password")) { "--account 仅用于查询或重置密码" }
        fun value(option: String): String = args.getOptionValues(option)?.singleOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("--$option 需要指定一个非空值")
        val username = if (args.containsOption("account")) value("account") else ""
        require(!(args.containsOption("show-password") || args.containsOption("reset-password")) || username.isNotEmpty()) { "没有默认账号，请使用 --account=账号 指定目标账号" }
        when {
            args.containsOption("import-legacy") -> {
                imports.importFile(Path.of(value("import-legacy")))
                audit.record(AuditService.commandActor, "导入旧稿件", "import", "legacy", "旧投稿数据导入")
            }
            args.containsOption("reset-password") -> auth.resetPassword(username)
            args.containsOption("show-password") -> println("账号 $username 的密码：${auth.currentPassword(username)}")
            args.containsOption("add-account") -> auth.addAccount(value("add-account"))
            args.containsOption("delete-account") -> auth.deleteAccount(value("delete-account"))
            args.containsOption("list-accounts") -> auth.listAccounts().let { names ->
                if (names.isEmpty()) println("暂无账号，请使用 --add-account=账号 添加。") else names.forEach { println("$it\t${AuthService.ADMIN_ROLE}") }
                audit.record(AuditService.commandActor, "查询账号列表", "account", "all", "共 ${names.size} 个账号")
            }
            args.containsOption("demo-data") -> demo.generate()
        }
    }
}

fun main(args: Array<String>) {
    // Windows 管道的默认编码可能不同，统一命令输出为 UTF-8，支持中文密码。
    System.setOut(PrintStream(System.out, true, Charsets.UTF_8))
    if ("--help" in args) {
        println("""
            启动：java -jar target/cczx-RadioStation.jar
            添加账号：--add-account=账号（默认密码 cczx123456，全部为管理员）
            列出账号：--list-accounts
            删除账号：--delete-account=账号
            查询密码：--show-password --account=账号
            重置密码：--reset-password --account=账号
            演示数据：--demo-data
            旧数据导入：--import-legacy=文件路径
            帮助：--help
            无默认账号；管理命令执行后退出，不启动网站。
        """.trimIndent())
        return
    }
    // 连接 SQLite 前先创建目录，避免首次启动时连接池找不到文件。
    Files.createDirectories(Path.of(System.getenv("DATA_DIR") ?: "./data"))
    val command = args.any { it.substringBefore('=') in managementOptions.map { option -> "--$option" } }
    var launchArgs = if (command) args.filterNot { it.substringBefore('=') == "--spring.main.web-application-type" }.toTypedArray() + "--spring.main.web-application-type=none" else args
    if (args.any { it.substringBefore('=') == "--show-password" }) {
        if (args.none { it.substringBefore('=') == "--spring.main.banner-mode" }) launchArgs += "--spring.main.banner-mode=off"
        if (args.none { it.substringBefore('=') == "--logging.level.root" }) launchArgs += "--logging.level.root=ERROR"
    }
    val context = runApplication<Application>(*launchArgs)
    if (command) context.close()
}
