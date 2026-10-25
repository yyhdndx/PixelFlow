package com.pixelflow.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_POST_IDEMPOTENCY_RESTART_URL",matches="jdbc:mysql:.*")
class MySqlPostIdempotencyRestartTest extends PostIdempotencyRestartTest {
    @Override @Test void fileDatabaseUpgradeAndActualHttpServerRestart() throws Exception {
        exercise(MySqlBusinessApiTest.testUrl("MYSQL_TEST_POST_IDEMPOTENCY_RESTART_URL"),
                System.getenv().getOrDefault("MYSQL_TEST_USER","root"),System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""),"mysql");
    }
}
