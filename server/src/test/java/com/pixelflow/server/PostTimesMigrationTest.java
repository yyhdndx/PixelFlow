package com.pixelflow.server;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PostTimesMigrationTest {
    @TempDir Path directory;
    String url() { return "jdbc:h2:file:"+directory.resolve("times").toString().replace('\\','/')+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"; }
    String user() { return "sa"; }
    String password() { return ""; }
    String location() { return "classpath:db/h2"; }

    @Test void upgradesV12AndPersistsOnlyPublishedBackfill() throws Exception {
        String url=url();
        try (var connection=DriverManager.getConnection(url,user(),password())) {
            if (url.startsWith("jdbc:mysql:")) {
                assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.4."));
                try (var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"posts",new String[]{"TABLE"})) {
                    assertFalse(tables.next(),"Migration test requires an empty dedicated database");
                }
            }
        }
        Flyway.configure().dataSource(url,user(),password()).locations(location()).target("12").load().migrate();
        var states=List.of("DRAFT","PENDING","REJECTED","PUBLISHED","WITHDRAWN");
        try (var connection=DriverManager.getConnection(url,user(),password()); var statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users(id,username,password_hash,nickname,role,avatar) VALUES('times_legacy','times_legacy','hash','旧作者','CREATOR','')");
            try (var insert=connection.prepareStatement("INSERT INTO posts(id,author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,'times_legacy','旧标题','正文','[]',FALSE,?,?)")) {
                for (int i=0;i<states.size();i++) { insert.setLong(1,i+1); insert.setString(2,states.get(i)); insert.setLong(3,100+i); insert.executeUpdate(); }
            }
        }
        Flyway.configure().dataSource(url,user(),password()).locations(location()).load().migrate();
        for (int reopen=0;reopen<2;reopen++) {
            try (var connection=DriverManager.getConnection(url,user(),password()); var statement=connection.createStatement();
                 var rows=statement.executeQuery("SELECT status,create_time,submit_time,review_time,publish_time,COALESCE(publish_time,create_time) AS display_time FROM posts ORDER BY id")) {
                int count=0;
                while (rows.next()) {
                    assertEquals(states.get(count),rows.getString("status")); assertEquals(100+count,rows.getLong("create_time"));
                    assertNull(rows.getObject("submit_time")); assertNull(rows.getObject("review_time"));
                    if (rows.getString("status").equals("PUBLISHED")) assertEquals(rows.getLong("create_time"),rows.getLong("publish_time"));
                    else assertNull(rows.getObject("publish_time"));
                    assertEquals(rows.getLong("create_time"),rows.getLong("display_time")); count++;
                }
                assertEquals(5,count);
                boolean index=false;
                try (var indexes=connection.getMetaData().getIndexInfo(connection.getCatalog(),location().endsWith("h2") ? "public" : null,"posts",false,false)) {
                    while (indexes.next()) if ("posts_search_title".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) index=true;
                }
                assertTrue(index);
            }
        }
    }
}
