package com.pixelflow.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.content.DemoData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:demo-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=true","pixelflow.admin-password=","pixelflow.public-base-url=http://127.0.0.1:8080"})
@AutoConfigureMockMvc
class DemoAssetsTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired DemoData seeds;

    @Test void allBundledFilesMatchCatalogAndImageDimensions() throws Exception {
        try (var input=new ClassPathResource("static/demo-assets/catalog.json").getInputStream()) {
            var assets=json.readTree(input).path("assets");
            assertEquals(24,assets.size());
            for (var asset:assets) {
                try (var data=new ClassPathResource("static/demo-assets/"+asset.path("file").asText()).getInputStream()) {
                    byte[] bytes=data.readAllBytes();
                    assertEquals(asset.path("bytes").asInt(),bytes.length);
                    assertEquals(asset.path("sha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                    if (!asset.path("kind").asText().equals("video")) {
                        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                        assertNotNull(image);
                        assertEquals(asset.path("width").asInt(),image.getWidth());
                        assertEquals(asset.path("height").asInt(),image.getHeight());
                        image.flush();
                    }
                    assertFalse(asset.path("author").asText().isBlank());
                    assertTrue(asset.path("licenseUrl").asText().startsWith("https://creativecommons.org/"));
                }
            }
        }
    }

    @Test void seedBatchesAreIdempotentAndUseSixAvatarsAndSevenVideoPosts() throws Exception {
        seeds.run(null);
        assertEquals(33,db.queryForObject("SELECT COUNT(*) FROM posts",Integer.class));
        assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM seed_batches",Integer.class));
        var result=mvc.perform(get("/feed/?count=50&accept_video_clip=true")).andExpect(status().isOk()).andReturn();
        var posts=json.readTree(result.getResponse().getContentAsByteArray()).path("post_list");
        var authors=new HashSet<String>(); int videos=0;
        for (var post:posts) {
            authors.add(post.path("author").path("user_id").asText());
            String avatar=post.path("author").path("avatar").asText();
            assertTrue(avatar.startsWith("http://127.0.0.1:8080/demo-assets/avatar-"));
            assertTrue(post.path("content").asText().contains("许可链接"));
            assertEquals(0,post.path("clips").get(0).path("type").asInt());
            for (var clip:post.path("clips")) if (clip.path("type").asInt()==1) videos++;
        }
        assertEquals(6,authors.size()); assertEquals(7,videos);
        mvc.perform(get("/feed/?count=50&accept_video_clip=false")).andExpect(jsonPath("post_list.length()").value(26));
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void v2AppendsToAnExistingV1DatabaseWithoutChangingExistingPosts() {
        db.update("DELETE FROM posts WHERE title LIKE '扩展素材 · %'");
        db.update("DELETE FROM seed_batches WHERE name='community-assets-v2'");
        var before=db.queryForList("SELECT id,title,content,clips_json FROM posts ORDER BY id");
        db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES('demo-media-author-1','保留的用户草稿','','[]',FALSE,'DRAFT',0)");
        seeds.run(null); seeds.run(null);
        assertEquals(before.size()+7,db.queryForObject("SELECT COUNT(*) FROM posts",Integer.class));
        assertEquals(before,db.queryForList("SELECT id,title,content,clips_json FROM posts WHERE title NOT LIKE '扩展素材 · %' AND title<>'保留的用户草稿' ORDER BY id"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM posts WHERE title='保留的用户草稿' AND status='DRAFT'",Integer.class));
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void v3AppendsOnlyThreePostsAndPreservesExistingContent() throws Exception {
        db.update("DELETE FROM posts WHERE title LIKE '生活素材 · %'");
        db.update("DELETE FROM seed_batches WHERE name='community-assets-v3'");
        var before=db.queryForList("SELECT id,title,content,clips_json,status FROM posts ORDER BY id");
        var authors=db.queryForList("SELECT id,username,nickname,avatar FROM users ORDER BY id");
        seeds.run(null); seeds.run(null);
        assertEquals(before.size()+3,db.queryForObject("SELECT COUNT(*) FROM posts",Integer.class));
        assertEquals(before,db.queryForList("SELECT id,title,content,clips_json,status FROM posts WHERE title NOT LIKE '生活素材 · %' ORDER BY id"));
        assertEquals(authors,db.queryForList("SELECT id,username,nickname,avatar FROM users ORDER BY id"));
        var rows=db.queryForList("SELECT clips_json FROM posts WHERE title LIKE '生活素材 · %'");
        var files=new HashSet<String>();
        for (var row:rows) {
            var clips=json.readTree(row.get("clips_json").toString());
            assertEquals(1,clips.size());
            var clip=clips.get(0);
            assertEquals(0,clip.path("type").asInt());
            files.add(clip.path("url").asText());
        }
        assertEquals(java.util.Set.of("http://127.0.0.1:8080/demo-assets/succulent.jpg",
                "http://127.0.0.1:8080/demo-assets/keyboard.jpg",
                "http://127.0.0.1:8080/demo-assets/basketball-court.jpg"),files);
    }

    @Test void publicResourcesSupportPlaybackRangesAndAttribution() throws Exception {
        mvc.perform(get("/demo-assets/avatar-1.png")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/demo-assets/coffee.jpg")).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        mvc.perform(get("/demo-assets/flowers.mp4").header("Range","bytes=0-31"))
                .andExpect(status().isPartialContent()).andExpect(content().contentType("video/mp4"))
                .andExpect(header().string("Content-Range","bytes 0-31/1257490"));
        mvc.perform(get("/demo-assets/flowers.mp4").header("Range","bytes=999999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(head("/demo-assets/waterfall.mp4")).andExpect(status().isOk());
        mvc.perform(get("/demo-assets/sunset.mp4").header("Range","bytes=0-31"))
                .andExpect(status().isPartialContent()).andExpect(content().contentType("video/mp4"));
        mvc.perform(get("/demo-assets/CREDITS.txt")).andExpect(status().isOk());
        mvc.perform(get("/demo-assets/missing.jpg")).andExpect(status().isNotFound());
    }
}
