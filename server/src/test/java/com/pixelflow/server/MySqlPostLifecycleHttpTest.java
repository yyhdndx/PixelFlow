package com.pixelflow.server;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_POST_LIFECYCLE_URL",matches="jdbc:mysql:.*")
class MySqlPostLifecycleHttpTest extends PostLifecycleHttpTest {
    @Override String databaseUrl() {
        String url=MySqlBusinessApiTest.testUrl("MYSQL_TEST_POST_LIFECYCLE_URL");
        URI uri=URI.create(url.substring(5));
        if (!java.util.Set.of("localhost","127.0.0.1").contains(uri.getHost()) || uri.getUserInfo()!=null)
            throw new IllegalArgumentException("Lifecycle tests require a dedicated local MySQL database");
        for (var entry:System.getenv().entrySet()) {
            if (entry.getKey().startsWith("MYSQL_TEST_") && entry.getKey().endsWith("_URL")
                    && !entry.getKey().equals("MYSQL_TEST_POST_LIFECYCLE_URL") && entry.getValue().startsWith("jdbc:mysql:")) {
                URI other=URI.create(entry.getValue().substring(5));
                if (uri.getPath().equalsIgnoreCase(other.getPath()))
                    throw new IllegalArgumentException("Lifecycle database must be independent of other MySQL suites");
            }
        }
        return url;
    }
    @Override String databaseUser() { return System.getenv().getOrDefault("MYSQL_TEST_USER","root"); }
    @Override String databasePassword() { return System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""); }
    @Override boolean mysql() { return true; }
}
