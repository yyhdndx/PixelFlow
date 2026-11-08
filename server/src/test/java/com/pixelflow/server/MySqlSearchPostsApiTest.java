package com.pixelflow.server;

import com.pixelflow.server.content.SearchPostsQueryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_SEARCH_URL",matches="jdbc:mysql:.*")
class MySqlSearchPostsApiTest extends SearchPostsApiTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> MySqlBusinessApiTest.testUrl("MYSQL_TEST_SEARCH_URL"));
        registry.add("spring.datasource.username",() -> System.getenv().getOrDefault("MYSQL_TEST_USER","root"));
        registry.add("spring.datasource.password",() -> System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""));
    }

    @BeforeAll static void requireMySql84(@Autowired JdbcTemplate db) {
        assertTrue(db.queryForObject("SELECT VERSION()",String.class).startsWith("8.4."));
    }

    @Test void indexMigrationAndFullQueryPlan() {
        assertEquals(30,db.queryForObject("SELECT SUB_PART FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='posts' AND index_name='posts_search_title'",Integer.class));
        fixture(9,"摄影","","PUBLISHED"); fixture(10,"无关","正文 #摄影","PUBLISHED");
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='12' AND success=TRUE",Integer.class)>0);
        System.out.println("SEARCH_EXPLAIN_FIRST "+db.queryForList("EXPLAIN "+SearchPostsQueryService.sql(false),"%摄影%","%摄影%",21));
        System.out.println("SEARCH_EXPLAIN_CURSOR "+db.queryForList("EXPLAIN "+SearchPostsQueryService.sql(true),"%摄影%","%摄影%",10L,21));
    }
}
