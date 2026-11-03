package com.pixelflow.server;

import com.pixelflow.server.content.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheHttpTest extends PostLifecycleHttpTest {
    @Override String databaseUrl() { return "jdbc:h2:mem:cache_"+run+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"; }

    @Test void warmedContentWritesRollbackAndViewerIsolation() throws Exception {
        try (var app=(ServletWebServerApplicationContext)new SpringApplicationBuilder(ServerApplication.class).run(
                "--server.address=127.0.0.1","--server.port=0","--spring.datasource.url="+databaseUrl(),
                "--spring.datasource.username="+databaseUser(),"--spring.datasource.password="+databasePassword(),
                "--pixelflow.seed=false","--pixelflow.admin-password="+password,
                "--pixelflow.rate-limit.enabled=false","--pixelflow.local-cache.enabled=true",
                "--pixelflow.local-cache.ttl=10m","--pixelflow.jwt-secret="+UUID.randomUUID())) {
            port=app.getWebServer().getPort(); db=app.getBean(JdbcTemplate.class); usernames.add("admin");
            if (mysql()) assertTrue(db.queryForObject("SELECT VERSION()",String.class).startsWith("8.4."));
            try {
                author=register("a","作者甲"); visitor=register("b","访问者乙"); admin=login("admin");
                authorId=body(send("GET","/api/users/me",author,null,null),200).get("id").asText();
                String media=upload("png").get("mediaId").asText();
                String id=body(send("POST","/api/posts",author,null,draft("旧标题","旧正文",List.of(media))),201).get("post_id").asText();
                var cache=app.getBean(PostContentCache.class);
                detail(id,author); long hits=cache.stats().hitCount(); detail(id,author);
                assertTrue(cache.stats().hitCount()>hits);
                String replacement=upload("png").get("mediaId").asText();
                body(send("PUT","/api/posts/"+id,author,null,draft("新标题","新正文",List.of(replacement,media))),200);
                var edited=detail(id,author);
                assertEquals("新标题",edited.get("title").asText());
                assertEquals("新正文",edited.get("content").asText());
                assertEquals(json.valueToTree(List.of(replacement,media)),edited.get("media_ids"));
                assertEquals(2,edited.get("clips").size());
                assertTrue(edited.get("clips").get(0).get("url").asText().contains(replacement));
                var posts=app.getBean(PostService.class);
                var actor=new com.pixelflow.server.auth.AuthService.Actor(authorId,"CREATOR","unused");
                new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx -> {
                    posts.edit(Long.parseLong(id),actor,new PostService.Draft("回滚标题","回滚正文",List.of(),List.of(media)));
                    tx.setRollbackOnly();
                });
                assertEquals("新标题",detail(id,author).get("title").asText());
                posts.applyAssist(Long.parseLong(id),actor,"建议标题","建议正文",detail(id,author).get("content_version").asLong());
                assertEquals("建议标题",detail(id,author).get("title").asText());
                assertStatus(id,"publish","PENDING"); moderate(id,"PUBLISHED",200);
                detail(id,visitor); detail(id,null);
                body(send("PUT","/api/interactions/likes",visitor,null,json.writeValueAsString(Map.of("targetId",id,"active",true))),200);
                assertTrue(detail(id,visitor).get("is_liked").asBoolean());
                assertFalse(detail(id,author).get("is_liked").asBoolean());
                assertFalse(detail(id,null).get("is_liked").asBoolean());
                assertEquals(1,detail(id,null).get("like_count").asInt());
                body(send("PUT","/api/interactions/favorites",visitor,null,json.writeValueAsString(Map.of("targetId",id,"active",true))),200);
                assertTrue(detail(id,visitor).get("is_favorited").asBoolean());
                assertFalse(detail(id,author).get("is_favorited").asBoolean());
                assertFalse(detail(id,null).get("is_favorited").asBoolean());
                String comment=body(send("POST","/api/posts/"+id+"/comments",visitor,null,"{\"content\":\"评论\"}"),200).get("comment_id").asText();
                assertEquals(1,detail(id,null).get("comment_count").asInt());
                body(send("PUT","/api/admin/comments/"+comment+"/status",admin,null,"{\"status\":\"HIDDEN\"}"),200);
                assertEquals(0,detail(id,null).get("comment_count").asInt());
                body(send("PUT","/api/admin/comments/"+comment+"/status",admin,null,"{\"status\":\"VISIBLE\"}"),200);
                assertEquals(1,detail(id,null).get("comment_count").asInt());
                body(send("DELETE","/api/comments/"+comment,visitor,null,null),200);
                assertEquals(0,detail(id,null).get("comment_count").asInt());
                body(send("PUT","/api/users/me",author,null,"{\"nickname\":\"新昵称\",\"bio\":\"\"}"),200);
                assertEquals("新昵称",detail(id,visitor).get("author").get("nickname").asText());
                body(send("PUT","/api/admin/users/"+authorId+"/status",admin,null,"{\"enabled\":false,\"reason\":\"缓存验收\"}"),200);
                error(send("GET","/api/posts/"+id,visitor,null,null),404,"POST_NOT_FOUND");
                body(send("PUT","/api/admin/users/"+authorId+"/status",admin,null,"{\"enabled\":true,\"reason\":\"恢复\"}"),200);
                author=login(usernames.stream().filter(name -> name.endsWith("a")).findFirst().orElseThrow());
                assertStatus(id,"withdraw","WITHDRAWN");
                error(send("GET","/api/posts/"+id,null,null,null),404,"POST_NOT_FOUND");
                assertEquals("建议标题",detail(id,author).get("title").asText());
            } finally {
                db.update("DELETE FROM user_status_audit WHERE user_id IN (SELECT id FROM users WHERE username=?)","lc_"+run+"a");
                cleanup();
            }
        }
    }
}
