package com.cczxcsc

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["app.admin-password=ApiTestStrong123", "app.submissions-per-minute=1000"])
class ApiSqliteTest : ApiContract() {
    companion object {
        private val directory = Files.createDirectories(Path.of("target/test-data", UUID.randomUUID().toString())).toAbsolutePath()
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${directory.resolve("test.db")}" }
            registry.add("app.data-dir") { directory.toString() }
            registry.add("app.secrets-dir") { directory.toString() }
        }
    }
}
