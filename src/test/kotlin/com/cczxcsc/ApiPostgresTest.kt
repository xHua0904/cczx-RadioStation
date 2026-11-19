package com.cczxcsc

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "app.admin-password=ApiTestStrong123", "app.submissions-per-minute=1000", "app.storage=postgres", "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/postgres", "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.datasource.hikari.maximum-pool-size=5", "spring.datasource.hikari.connection-init-sql=SELECT 1"
])
class ApiPostgresTest : ApiContract() {
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:17-alpine")
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("app.data-dir") { Files.createDirectories(Path.of("target/test-postgres")).toAbsolutePath().toString() }
            registry.add("app.secrets-dir") { Files.createDirectories(Path.of("target/test-postgres")).toAbsolutePath().toString() }
        }
    }
}
