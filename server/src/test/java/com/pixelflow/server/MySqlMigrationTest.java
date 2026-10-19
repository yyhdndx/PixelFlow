package com.pixelflow.server;

import com.pixelflow.server.tools.MigrateH2ToMysql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_MIGRATION_URL", matches="jdbc:mysql:.*")
class MySqlMigrationTest {
    @TempDir Path directory;
    @Test void importsV6WithDefaultBio() throws Exception {
        importVersion("6", "MYSQL_TEST_MIGRATION_URL", "");
    }

    @Test @EnabledIfEnvironmentVariable(named="MYSQL_TEST_MIGRATION_V7_URL", matches="jdbc:mysql:.*")
    void importsV7PreservingBio() throws Exception {
        importVersion("7", "MYSQL_TEST_MIGRATION_V7_URL", "中文😀\n简介");
    }

    private void importVersion(String version, String targetVariable, String bio) throws Exception {
        String source = "jdbc:h2:file:" + directory.resolve("source-" + version).toString().replace('\\','/')
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        String target = MySqlBusinessApiTest.testUrl(targetVariable);
        String user = System.getenv().getOrDefault("MYSQL_TEST_USER", "root");
        String password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        Flyway.configure().dataSource(source,"sa", "").locations("classpath:db/h2").target(version).load().migrate();
        try (var db = DriverManager.getConnection(source,"sa", ""); var st = db.createStatement()) {
            if (version.equals("6")) {
                st.executeUpdate("INSERT INTO users(id,username,password_hash,nickname,role,enabled,avatar) VALUES('alice','alice','hash','中文😀','CREATOR',TRUE,'')");
            } else {
                try (var insert = db.prepareStatement("INSERT INTO users(id,username,password_hash,nickname,role,enabled,avatar,bio) VALUES('alice','alice','hash','中文😀','CREATOR',TRUE,'',?)")) {
                    insert.setString(1, bio); insert.executeUpdate();
                }
            }
            st.executeUpdate("INSERT INTO posts VALUES(42,'alice','title','content','[]',FALSE,'PUBLISHED',100)");
            st.executeUpdate("INSERT INTO behavior_events(seq,event_id,user_id,post_id,event_type,occurred_at,received_at) VALUES(7,'e','alice',42,'CLICK',100,100)");
            st.executeUpdate("UPDATE event_export_state SET last_seq=7,source_id='preserved-source'");
            try (var insert = db.prepareStatement("INSERT INTO media VALUES('m','alice',1,1,?,100)")) {
                insert.setBytes(1,new byte[]{0,1,2,-1}); insert.executeUpdate();
            }
        }
        MigrateH2ToMysql.migrate(source,"sa","",target,user,password);
        try (var db = DriverManager.getConnection(target,user,password); var st = db.createStatement()) {
            try (var rs = st.executeQuery("SELECT nickname,bio,avatar FROM users WHERE id='alice'")) {
                assertTrue(rs.next()); assertEquals("中文😀",rs.getString(1));
                assertEquals(bio,rs.getString(2)); assertEquals("",rs.getString(3));
            }
            try (var rs = st.executeQuery("SELECT source_id,last_seq FROM event_export_state")) {
                assertTrue(rs.next()); assertEquals("preserved-source",rs.getString(1)); assertEquals(7,rs.getLong(2));
            }
            try (var rs = st.executeQuery("SELECT content_bytes FROM media")) {
                assertTrue(rs.next()); assertArrayEquals(new byte[]{0,1,2,-1},rs.getBytes(1));
            }
            st.executeUpdate("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES('alice','next','body','[]',FALSE,'DRAFT',101)");
            try (var rs = st.executeQuery("SELECT MAX(id) FROM posts")) { rs.next(); assertTrue(rs.getLong(1)>42); }
        }
        assertThrows(IllegalStateException.class, () -> MigrateH2ToMysql.migrate(source,"sa","",target,user,password));
        try (var db = DriverManager.getConnection(source,"sa",""); var st = db.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM posts")) { rs.next(); assertEquals(1,rs.getInt(1)); }
    }
}
