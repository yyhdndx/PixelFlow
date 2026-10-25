package com.pixelflow.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_POST_IDEMPOTENCY_URL",matches="jdbc:mysql:.*")
class MySqlPostIdempotencyApiTest extends PostIdempotencyApiTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> MySqlBusinessApiTest.testUrl("MYSQL_TEST_POST_IDEMPOTENCY_URL"));
        registry.add("spring.datasource.username",() -> System.getenv().getOrDefault("MYSQL_TEST_USER","root"));
        registry.add("spring.datasource.password",() -> System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""));
    }
    @BeforeAll static void requireMySql84(@Autowired JdbcTemplate db) {
        assertTrue(db.queryForObject("SELECT VERSION()",String.class).startsWith("8.4."));
    }
}
