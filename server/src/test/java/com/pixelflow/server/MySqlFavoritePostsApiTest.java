package com.pixelflow.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.DriverManager;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_FAVORITES_URL", matches="jdbc:mysql:.*")
class MySqlFavoritePostsApiTest extends FavoritePostsApiTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MySqlBusinessApiTest.testUrl("MYSQL_TEST_FAVORITES_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("MYSQL_TEST_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", ""));
    }

    @BeforeAll static void databaseVersion() throws Exception {
        try (var connection=DriverManager.getConnection(MySqlBusinessApiTest.testUrl("MYSQL_TEST_FAVORITES_URL"),
                System.getenv().getOrDefault("MYSQL_TEST_USER","root"),System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""))) {
            System.out.println("Favorites database: "+connection.getMetaData().getDatabaseProductName()+" "+connection.getMetaData().getDatabaseProductVersion());
        }
    }
}
