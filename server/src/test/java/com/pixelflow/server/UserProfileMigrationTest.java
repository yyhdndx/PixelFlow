package com.pixelflow.server;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class UserProfileMigrationTest {
    @TempDir Path directory;

    @Test void upgradesClosedV6FileAndKeepsProfileAfterReopen() throws Exception {
        String url="jdbc:h2:file:"+directory.resolve("profile").toString().replace('\\','/')
                +";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/h2").target("6").load().migrate();
        try (var db=DriverManager.getConnection(url,"sa",""); var st=db.createStatement()) {
            st.executeUpdate("INSERT INTO users(id,username,password_hash,nickname,role,avatar) "
                    +"VALUES('old','old','hash','旧名字','CREATOR','/avatar.png')");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/h2").load().migrate();
        try (var db=DriverManager.getConnection(url,"sa",""); var st=db.createStatement()) {
            try (var row=st.executeQuery("SELECT nickname,bio,avatar FROM users WHERE id='old'")) {
                assertTrue(row.next()); assertEquals("旧名字",row.getString(1));
                assertEquals("",row.getString(2)); assertEquals("/avatar.png",row.getString(3));
            }
            st.executeUpdate("UPDATE users SET nickname='新名字',bio='  中文\n😀  ' WHERE id='old'");
        }
        try (var db=DriverManager.getConnection(url,"sa",""); var st=db.createStatement();
             var row=st.executeQuery("SELECT nickname,bio,avatar FROM users WHERE id='old'")) {
            assertTrue(row.next()); assertEquals("新名字",row.getString(1));
            assertEquals("  中文\n😀  ",row.getString(2)); assertEquals("/avatar.png",row.getString(3));
        }
    }
}
