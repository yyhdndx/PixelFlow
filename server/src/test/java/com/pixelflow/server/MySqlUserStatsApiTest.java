package com.pixelflow.server;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_STATS_URL", matches="jdbc:mysql:.*")
class MySqlUserStatsApiTest extends UserStatsApiTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MySqlBusinessApiTest.testUrl("MYSQL_TEST_STATS_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("MYSQL_TEST_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", ""));
    }
}
