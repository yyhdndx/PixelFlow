package com.pixelflow.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_SEARCH_URL",matches="jdbc:mysql:.*")
class MySqlSearchPostsHttpTest {
    @Test void searchesPublishedPostsThroughRealHttp() throws Exception {
        var h=new PostLifecycleHttpTest();
        try (var application=(ServletWebServerApplicationContext)new SpringApplicationBuilder(ServerApplication.class).run(
                "--server.address=127.0.0.1","--server.port=0",
                "--spring.datasource.url="+MySqlBusinessApiTest.testUrl("MYSQL_TEST_SEARCH_URL"),
                "--spring.datasource.username="+System.getenv().getOrDefault("MYSQL_TEST_USER","root"),
                "--spring.datasource.password="+System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""),
                "--pixelflow.seed=false","--pixelflow.admin-password="+h.password,
                "--pixelflow.rate-limit.enabled=false","--pixelflow.jwt-secret="+UUID.randomUUID())) {
            h.port=application.getWebServer().getPort(); h.db=application.getBean(JdbcTemplate.class);
            assertTrue(h.db.queryForObject("SELECT VERSION()",String.class).startsWith("8.4."));
            h.usernames.add("admin");
            try {
                h.author=h.register("a","搜索作者"); h.visitor=h.register("b","搜索访客"); h.admin=h.login("admin");
                h.authorId=h.body(h.send("GET","/api/users/me",h.author,null,null),200).get("id").asText();
                String media=h.upload("png").get("mediaId").asText();
                String keyword="检索"+h.run;
                List<String> ids=new ArrayList<>();
                for (String title:List.of(keyword+"标题","另一标题","隐藏标题")) {
                    String id=h.body(h.send("POST","/api/posts",h.author,null,
                            h.draft(title,"正文 #"+keyword+"话题",List.of(media))),201).get("post_id").asText();
                    if (ids.size()<2) {
                        h.assertStatus(id,"publish","PENDING"); h.moderate(id,"PUBLISHED",200);
                    }
                    ids.add(id);
                }
                String path="/api/search/posts?q="+URLEncoder.encode(keyword,StandardCharsets.UTF_8)+"&count=1";
                var first=h.body(h.send("GET",path,null,null,null),200);
                assertEquals(ids.get(1),first.get("post_list").get(0).get("post_id").asText());
                assertEquals(1,first.get("has_more").asInt());
                var last=h.body(h.send("GET",path+"&cursor="+first.get("next_cursor").asText(),null,null,null),200);
                assertEquals(ids.get(0),last.get("post_list").get(0).get("post_id").asText());
                assertEquals(0,last.get("has_more").asInt()); assertTrue(last.get("next_cursor").isNull());
                assertTrue(h.body(h.send("GET","/api/search/posts?q="+h.run+"absent",null,null,null),200).get("post_list").isEmpty());
                h.body(h.send("PUT","/api/interactions/likes",h.visitor,null,h.json.writeValueAsString(Map.of("targetId",ids.get(1),"active",true))),200);
                assertTrue(h.body(h.send("GET",path,h.visitor,null,null),200).get("post_list").get(0).get("is_liked").asBoolean());
                h.body(h.send("POST","/api/posts/"+ids.get(1)+"/withdraw",h.author,null,null),200);
                assertEquals(ids.get(0),h.body(h.send("GET",path,null,null,null),200).get("post_list").get(0).get("post_id").asText());
                System.out.println("SEARCH_HTTP hit=200 topic=200 first=has_more:1 last=has_more:0 empty=200 guest=200 viewer-state=passed withdrawn=hidden");
            } finally { h.cleanup(); }
        }
    }
}
