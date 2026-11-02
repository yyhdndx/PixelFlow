package com.pixelflow.server;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_POST_TIMES_MIGRATION_URL",matches="jdbc:mysql:.*")
class MySqlPostTimesMigrationTest extends PostTimesMigrationTest {
    @Override String url() { return MySqlBusinessApiTest.testUrl("MYSQL_TEST_POST_TIMES_MIGRATION_URL"); }
    @Override String user() { return System.getenv().getOrDefault("MYSQL_TEST_USER","root"); }
    @Override String password() { return System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""); }
    @Override String location() { return "classpath:db/mysql"; }
}
