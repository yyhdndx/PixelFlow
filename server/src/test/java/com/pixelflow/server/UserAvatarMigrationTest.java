package com.pixelflow.server;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class UserAvatarMigrationTest {
    @TempDir Path directory;
    @Test void v7AvatarAndBioSurviveUpgradeAndReferenceSurvivesReopen() throws Exception {
        verify("jdbc:h2:file:"+directory.resolve("avatar").toString().replace('\\','/')
                +";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE","sa","","classpath:db/h2");
    }
    static void verify(String url,String user,String password,String location) throws Exception {
        Flyway.configure().dataSource(url,user,password).locations(location).target("7").load().migrate();
        try (var db=DriverManager.getConnection(url,user,password); var st=db.createStatement()) {
            st.executeUpdate("INSERT INTO users(id,username,password_hash,nickname,role,avatar,bio)"
                    +" VALUES('old','old','hash','旧昵称','CREATOR','https://example.com/old.png','原简介')");
        }
        Flyway.configure().dataSource(url,user,password).locations(location).load().migrate();
        try (var db=DriverManager.getConnection(url,user,password); var st=db.createStatement()) {
            try (var row=st.executeQuery("SELECT avatar,bio,avatar_media_id FROM users WHERE id='old'")) {
                assertTrue(row.next()); assertEquals("https://example.com/old.png",row.getString(1));
                assertEquals("原简介",row.getString(2)); assertNull(row.getString(3));
            }
            assertThrows(SQLException.class,() -> st.executeUpdate("UPDATE users SET avatar_media_id='missing' WHERE id='old'"));
            try (var insert=db.prepareStatement("INSERT INTO media(id,owner_id,width,height,content_bytes,created_at) VALUES('image','old',1,1,?,1)")) {
                insert.setBytes(1,new byte[]{1,2}); insert.executeUpdate();
            }
            st.executeUpdate("UPDATE users SET avatar_media_id='image',avatar='/api/media/image/content' WHERE id='old'");
        }
        try (var db=DriverManager.getConnection(url,user,password); var st=db.createStatement()) {
            try (var row=st.executeQuery("SELECT avatar_media_id FROM users WHERE id='old'")) {
                assertTrue(row.next()); assertEquals("image",row.getString(1));
            }
            assertThrows(SQLException.class,() -> st.executeUpdate("DELETE FROM media WHERE id='image'"));
            st.executeUpdate("UPDATE users SET avatar_media_id=NULL,avatar='' WHERE id='old'");
            assertEquals(1,st.executeUpdate("DELETE FROM media WHERE id='image'"));
        }
        Flyway.configure().dataSource(url,user,password).locations(location).load().validate();
    }
}
