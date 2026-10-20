package com.pixelflow.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_AVATAR_MIGRATION_URL",matches="jdbc:mysql:.*")
class MySqlUserAvatarMigrationTest {
    @Test void upgradesV7AndEnforcesAvatarForeignKey() throws Exception {
        UserAvatarMigrationTest.verify(MySqlBusinessApiTest.testUrl("MYSQL_TEST_AVATAR_MIGRATION_URL"),
                System.getenv().getOrDefault("MYSQL_TEST_USER","root"),
                System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""),"classpath:db/mysql");
    }
}
