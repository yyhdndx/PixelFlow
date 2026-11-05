package com.pixelflow.server;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_CACHE_URL",matches="jdbc:mysql:.*")
class MySqlCacheHttpTest extends CacheHttpTest {
    @Override String databaseUrl() { return MySqlBusinessApiTest.testUrl("MYSQL_TEST_CACHE_URL"); }
    @Override String databaseUser() { return System.getenv().getOrDefault("MYSQL_TEST_USER","root"); }
    @Override String databasePassword() { return System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""); }
    @Override boolean mysql() { return true; }
}
