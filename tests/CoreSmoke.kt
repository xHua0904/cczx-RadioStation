package com.cczxcsc

// 无需 Maven 依赖即可检查真实 Kotlin 核心逻辑，完整接口测试仍需运行 mvnw test。
fun main() {
    val old = "pbkdf2_sha256\$120000\$00112233445566778899aabbccddeeff\$bf4ddf9dd8a39d8d3b946b11bcdd2ebca0065a618088d41f35a697fccc213e02"
    check(Passwords.verify("校园Test123", old)) { "旧 Python 中文密码不兼容" }
    check(!Passwords.verify("错误密码", old))
    check(!Passwords.verify("test", "pbkdf2_sha256\$999999999\$bad\$bad"))
    val input = SubmissionInput(author = "测试同学", className = "高一一班", content = "校园里的每一天都值得记录。")
    val clean = Validation.submission(input)
    check(clean.category == "其他" && clean.content == input.content && clean.period == Weeks.current().key)
    check(Validation.submission(input.copy(content = "稿".repeat(100))).content.length == 100)
    check(runCatching { Validation.submission(input.copy(content = "稿".repeat(101))) }.exceptionOrNull() is ApiError)
    check(runCatching { Validation.submission(input.copy(content = " ")) }.exceptionOrNull() is ApiError)
    check(Weeks.key(java.time.LocalDate.of(2021, 1, 1)) == "2020-W53")
    check(runCatching { Validation.date("2026-02-30") }.exceptionOrNull() is ApiError)
    check(Validation.date("2028-02-29") == "2028-02-29")
    check(Validation.submission(input.copy(status = "已播出")).status == "待审核")
    check(Passwords.token() != Passwords.token())
    println("Kotlin 核心验证通过：旧密码兼容、投稿字数、周次、日期、状态及令牌。")
}
