package com.pixelflow.server;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Reuse the API contract suite against a dedicated disposable MySQL database. */
@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL", matches="jdbc:mysql:.*")
class MySqlBusinessApiTest extends BusinessApiTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> testUrl("MYSQL_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("MYSQL_TEST_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", ""));
    }

    static String testUrl(String variable) {
        String url = System.getenv(variable);
        if (url == null || !java.net.URI.create(url.substring(5)).getPath().endsWith("_test"))
            throw new IllegalArgumentException("MySQL integration tests require a disposable database ending in _test");
        return url;
    }
}
