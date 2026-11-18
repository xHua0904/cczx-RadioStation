package com.cczxcsc

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

@SpringBootApplication
class Application {
    @Bean
    @Order(1)
    fun startup(auth: AuthService, imports: LegacyImportService, demo: DemoService) = ApplicationRunner { args: ApplicationArguments ->
        require(listOf("import-legacy", "reset-password", "show-password", "demo-data", "add-account", "list-accounts").count(args::containsOption) <= 1) { "一次只能指定一个管理命令" }
        require(!args.containsOption("account") || args.containsOption("show-password") || args.containsOption("reset-password")) { "--account 仅用于查询或重置密码" }
        fun value(option: String): String = args.getOptionValues(option)?.singleOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("--$option 需要指定一个非空值")
        val username = if (args.containsOption("account")) value("account") else Passwords.DEFAULT_ACCOUNT
        if (!args.containsOption("import-legacy")) auth.initialize()
        when {
            args.containsOption("import-legacy") -> imports.importFile(Path.of(args.getOptionValues("import-legacy").first()))
            args.containsOption("reset-password") -> auth.resetPassword(username)
            args.containsOption("show-password") -> println("账号 $username 的密码：${auth.currentPassword(username)}")
            args.containsOption("add-account") -> auth.addAccount(value("add-account"))
            args.containsOption("list-accounts") -> auth.listAccounts().forEach(::println)
            args.containsOption("demo-data") -> { auth.initialize(); demo.generate() }
            else -> auth.initialize()
        }
    }
}

fun main(args: Array<String>) {
    // Windows 管道的默认编码可能不同，统一命令输出为 UTF-8，支持中文密码。
    System.setOut(PrintStream(System.out, true, Charsets.UTF_8))
    // 连接 SQLite 前先创建目录，避免首次启动时连接池找不到文件。
    Files.createDirectories(Path.of(System.getenv("DATA_DIR") ?: "./data"))
    val command = args.any { it.substringBefore('=') in setOf("--import-legacy", "--reset-password", "--show-password", "--demo-data", "--add-account", "--list-accounts") }
    var launchArgs = if (command) args.filterNot { it.substringBefore('=') == "--spring.main.web-application-type" }.toTypedArray() + "--spring.main.web-application-type=none" else args
    if (args.any { it.substringBefore('=') == "--show-password" }) {
        if (args.none { it.substringBefore('=') == "--spring.main.banner-mode" }) launchArgs += "--spring.main.banner-mode=off"
        if (args.none { it.substringBefore('=') == "--logging.level.root" }) launchArgs += "--logging.level.root=ERROR"
    }
    val context = runApplication<Application>(*launchArgs)
    if (command) context.close()
}
