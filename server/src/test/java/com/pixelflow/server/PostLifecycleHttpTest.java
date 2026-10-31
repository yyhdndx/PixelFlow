package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PostLifecycleHttpTest {
    final ObjectMapper json=new ObjectMapper();
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final String run=UUID.randomUUID().toString().replace("-","").substring(0,16);
    final String password=UUID.randomUUID().toString();
    final List<String> usernames=new ArrayList<>();
    JdbcTemplate db;
    int port;
    String authorId,author,visitor,admin;

    String databaseUrl() { return "jdbc:h2:mem:lifecycle_"+run+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"; }
    String databaseUser() { return "sa"; }
    String databasePassword() { return ""; }
    boolean mysql() { return false; }

    @Test void creationModerationVisibilityAndWithdrawalOverHttp() throws Exception {
        String url=databaseUrl();
        try (var connection=DriverManager.getConnection(url,databaseUser(),databasePassword())) {
            if (mysql()) {
                try (var statement=connection.createStatement(); var result=statement.executeQuery("SELECT VERSION()")) {
                    assertTrue(result.next()); assertTrue(result.getString(1).startsWith("8.4."));
                    System.out.println("POST_LIFECYCLE_MYSQL_VERSION="+result.getString(1));
                }
            }
            try (var tables=connection.getMetaData().getTables(connection.getCatalog(),mysql() ? null : "public","users",new String[]{"TABLE"})) {
                if (tables.next()) {
                    try (var statement=connection.prepareStatement("SELECT COUNT(*) FROM users WHERE username='admin'")) {
                        try (var result=statement.executeQuery()) {
                            assertTrue(result.next()); assertEquals(0,result.getInt(1),"Dedicated lifecycle database must not contain an existing administrator");
                        }
                    }
                }
            }
        }
        try (var application=(ServletWebServerApplicationContext)new SpringApplicationBuilder(ServerApplication.class).run(
                "--server.address=127.0.0.1","--server.port=0","--spring.datasource.url="+url,
                "--spring.datasource.username="+databaseUser(),"--spring.datasource.password="+databasePassword(),
                "--pixelflow.seed=false","--pixelflow.admin-password="+password,
                "--pixelflow.jwt-secret="+UUID.randomUUID(),"--pixelflow.public-base-url=http://127.0.0.1:8080")) {
            port=application.getWebServer().getPort(); db=application.getBean(JdbcTemplate.class);
            usernames.add("admin");
            try {
                assertEquals(List.of("1","2","3","4","5","6","7","9","10","11"),db.queryForList(
                        "SELECT version FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL ORDER BY installed_rank",String.class));
                author=register("a","作者甲"); visitor=register("b","访问者乙"); admin=login("admin");
                authorId=body(send("GET","/api/users/me",author,null,null),200).get("id").asText();
                lifecycle();
                rejection();
                System.out.println("POST_LIFECYCLE_HTTP database="+(mysql() ? "mysql" : "h2")
                        +" lost-response=201/200 draft=pending=published=withdrawn rejection=resubmission=passed isolation=passed media=passed");
            } finally { cleanup(); }
        }
    }

    void lifecycle() throws Exception {
        var png=upload("png"); var jpeg=upload("jpeg");
        var media=List.of(png.get("mediaId").asText(),jpeg.get("mediaId").asText());
        String original=draft("首次创作","中文正文\n保留空格 ",media),key=UUID.randomUUID().toString();
        var first=request("POST","/api/posts",author,key,original);
        assertEquals(201,client.send(first,HttpResponse.BodyHandlers.discarding()).statusCode());
        var recovered=body(send("POST","/api/posts",author,key,original),200);
        String id=recovered.get("post_id").asText();
        assertEquals("DRAFT",recovered.get("status").asText());
        verifySingleCreation(id,key,media);
        visibility(id,"DRAFT",List.of(png,jpeg));
        permissions(id,original,"DRAFT");
        String edited=draft("修改后的标题","修改后的正文",media);
        assertEquals("修改后的标题",body(send("PUT","/api/posts/"+id,author,null,edited),200).get("title").asText());
        assertEquals("修改后的正文",detail(id,author).get("content").asText());
        replay(id,key,original,"DRAFT","修改后的标题");
        assertStatus(id,"publish","PENDING"); assertStatus(id,"publish","PENDING");
        visibility(id,"PENDING",List.of(png,jpeg));
        permissions(id,edited,"PENDING");
        error(send("PUT","/api/posts/"+id,author,null,edited),409,"INVALID_STATE");
        replay(id,key,original,"PENDING","修改后的标题");
        moderate(id,"PUBLISHED",200);
        assertEquals(1,audits(id).size()); moderate(id,"PUBLISHED",200); assertEquals(1,audits(id).size());
        visibility(id,"PUBLISHED",List.of(png,jpeg));
        error(send("PUT","/api/posts/"+id,author,null,edited),409,"INVALID_STATE");
        error(send("POST","/api/posts/"+id+"/publish",author,null,null),409,"INVALID_STATE");
        moderate(id,"REJECTED",409); assertEquals("PUBLISHED",detail(id,author).get("status").asText());
        assertEquals(1,audits(id).size());
        for (String kind:List.of("likes","favorites"))
            body(send("PUT","/api/interactions/"+kind,visitor,null,json.writeValueAsString(Map.of("targetId",id,"active",true))),200);
        assertTrue(detail(id,visitor).get("is_liked").asBoolean());
        assertFalse(detail(id,author).get("is_liked").asBoolean());
        for (String path:List.of("/feed/?count=50&accept_video_clip=true","/api/users/"+authorId+"/posts?count=50","/api/users/me/favorites?count=50")) {
            var card=find(body(send("GET",path,visitor,null,null),200).get("post_list"),id);
            assertTrue(card.get("is_liked").asBoolean()); assertEquals(1,card.get("like_count").asInt());
            assertCard(card,id,media);
            if (path.contains("favorites")) assertTrue(card.get("is_favorited").asBoolean());
        }
        replay(id,key,original,"PUBLISHED","修改后的标题");
        assertStatus(id,"withdraw","WITHDRAWN");
        visibility(id,"WITHDRAWN",List.of(png,jpeg));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM interactions WHERE kind='favorites' AND target_id=?",Integer.class,id));
        replay(id,key,original,"WITHDRAWN","修改后的标题"); verifySingleCreation(id,key,media);
    }

    void rejection() throws Exception {
        var image=upload("png"); var media=List.of(image.get("mediaId").asText());
        String input=draft("驳回演示","需要修改的正文",media);
        String id=body(send("POST","/api/posts",author,UUID.randomUUID().toString(),input),201).get("post_id").asText();
        moderate(id,"PUBLISHED",409); assertEquals(0,audits(id).size());
        assertEquals("DRAFT",detail(id,author).get("status").asText());
        assertStatus(id,"publish","PENDING"); moderate(id,"REJECTED",200); moderate(id,"REJECTED",200);
        var rejected=audits(id); assertEquals(1,rejected.size()); audit(rejected.get(0),"PENDING","REJECTED");
        visibility(id,"REJECTED",List.of(image)); permissions(id,input,"REJECTED");
        var edited=body(send("PUT","/api/posts/"+id,author,null,draft("整改后标题","整改后正文",media)),200);
        assertEquals("DRAFT",edited.get("status").asText());
        assertStatus(id,"publish","PENDING"); assertStatus(id,"publish","PENDING");
        moderate(id,"PUBLISHED",200); moderate(id,"PUBLISHED",200);
        var records=audits(id); assertEquals(2,records.size());
        audit(records.get(0),"PENDING","REJECTED"); audit(records.get(1),"PENDING","PUBLISHED");
        assertEquals("整改后正文",detail(id,visitor).get("content").asText());
        visibility(id,"PUBLISHED",List.of(image));
    }

    void permissions(String id,String input,String state) throws Exception {
        error(send("PUT","/api/posts/"+id,visitor,null,input),403,"FORBIDDEN");
        for (String action:List.of("publish","withdraw"))
            error(send("POST","/api/posts/"+id+"/"+action,visitor,null,null),403,"FORBIDDEN");
        for (String token:List.of(author,visitor)) {
            error(send("POST","/api/admin/posts/"+id+"/moderation",token,null,"{\"status\":\"PUBLISHED\",\"reason\":\"非法审核\"}"),403,"FORBIDDEN");
            error(send("GET","/api/admin/posts",token,null,null),403,"FORBIDDEN");
            error(send("GET","/api/admin/moderation",token,null,null),403,"FORBIDDEN");
        }
        for (String token:Arrays.asList(null,"Bearer invalid")) {
            error(send("POST","/api/posts/"+id+"/publish",token,null,null),401,"UNAUTHORIZED");
            error(send("PUT","/api/posts/"+id,token,null,input),401,"UNAUTHORIZED");
            error(send("POST","/api/posts/"+id+"/withdraw",token,null,null),401,"UNAUTHORIZED");
        }
        assertEquals(state,detail(id,author).get("status").asText());
    }

    void visibility(String id,String state,List<JsonNode> images) throws Exception {
        boolean published=state.equals("PUBLISHED");
        assertEquals(state,detail(id,author).get("status").asText());
        assertEquals(state,find(body(send("GET","/api/posts?count=50",author,null,null),200),id).get("status").asText());
        assertFalse(contains(body(send("GET","/api/posts?count=50",visitor,null,null),200),id));
        for (String token:List.of(author,visitor)) {
            for (String path:List.of("/feed/?count=50&accept_video_clip=true","/api/users/"+authorId+"/posts?count=50")) {
                var response=send("GET",path,token,null,null);
                assertEquals(published,contains(body(response,200).get("post_list"),id),path+" "+state);
                if (path.startsWith("/api/users/")) assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            }
        }
        var favorites=send("GET","/api/users/me/favorites?count=50",visitor,null,null);
        assertEquals("no-store",favorites.headers().firstValue("Cache-Control").orElseThrow());
        if (!published) assertFalse(contains(body(favorites,200).get("post_list"),id));
        for (String token:Arrays.asList(visitor,null)) {
            if (published) assertEquals(state,detail(id,token).get("status").asText());
            else error(send("GET","/api/posts/"+id,token,null,null),404,"POST_NOT_FOUND");
        }
        for (var image:images) {
            String path=URI.create(image.get("url").asText()).getRawPath();
            assertEquals("/api/media/"+image.get("mediaId").asText()+"/content",path);
            for (String token:Arrays.asList(author,visitor,null)) {
                var response=client.send(request("GET",path,token,null,null),HttpResponse.BodyHandlers.ofByteArray());
                if (published || Objects.equals(token,author)) {
                    assertEquals(200,response.statusCode());
                    assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
                    assertEquals("image/png",response.headers().firstValue("Content-Type").orElseThrow());
                    var decoded=ImageIO.read(new ByteArrayInputStream(response.body())); assertNotNull(decoded);
                    assertEquals(12,decoded.getWidth()); assertEquals(18,decoded.getHeight()); decoded.flush();
                } else {
                    assertEquals(404,response.statusCode()); assertEquals("MEDIA_NOT_FOUND",json.readTree(response.body()).get("code").asText());
                }
            }
        }
    }

    void verifySingleCreation(String id,String key,List<String> media) {
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM post_creation_idempotency WHERE user_id=? AND request_key=? AND post_id=?",Integer.class,authorId,key,Long.parseLong(id)));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM posts WHERE author_id=?",Integer.class,authorId));
        assertEquals(media,db.queryForList("SELECT media_id FROM post_media WHERE post_id=? ORDER BY position",String.class,Long.parseLong(id)));
    }
    void replay(String id,String key,String original,String state,String title) throws Exception {
        var post=body(send("POST","/api/posts",author,key,original),200);
        assertEquals(id,post.get("post_id").asText()); assertEquals(state,post.get("status").asText()); assertEquals(title,post.get("title").asText());
    }
    void assertStatus(String id,String action,String state) throws Exception {
        var result=body(send("POST","/api/posts/"+id+"/"+action,author,null,null),200);
        assertEquals(id,result.get("postId").asText()); assertEquals(state,result.get("status").asText());
    }
    void moderate(String id,String state,int status) throws Exception {
        var response=send("POST","/api/admin/posts/"+id+"/moderation",admin,null,json.writeValueAsString(Map.of("status",state,"reason","本次链路验收")));
        if (status==200) { assertEquals(state,body(response,200).get("status").asText()); }
        else error(response,status,"INVALID_STATE");
    }
    List<JsonNode> audits(String id) throws Exception {
        List<JsonNode> result=new ArrayList<>();
        for (var record:body(send("GET","/api/admin/moderation?count=50",admin,null,null),200))
            if (record.get("post_id").asText().equals(id)) result.add(record);
        return result;
    }
    void audit(JsonNode record,String before,String after) {
        assertEquals(before,record.get("before_status").asText()); assertEquals(after,record.get("after_status").asText());
        assertEquals("本次链路验收",record.get("reason").asText());
    }
    void assertCard(JsonNode card,String id,List<String> media) throws Exception {
        assertEquals(id,card.get("post_id").asText()); assertEquals("修改后的标题",card.get("title").asText());
        assertEquals("修改后的正文",card.get("content").asText()); assertEquals("PUBLISHED",card.get("status").asText());
        assertEquals(authorId,card.get("author").get("user_id").asText()); assertEquals("作者甲",card.get("author").get("nickname").asText());
        assertEquals(json.valueToTree(media),card.get("media_ids")); assertEquals(media.size(),card.get("clips").size());
        for (int i=0;i<media.size();i++) assertEquals("/api/media/"+media.get(i)+"/content",URI.create(card.get("clips").get(i).get("url").asText()).getPath());
        assertTrue(card.has("create_time")); assertTrue(card.has("hashtag")); assertTrue(card.has("music"));
    }
    JsonNode upload(String format) throws Exception {
        var image=new BufferedImage(12,18,BufferedImage.TYPE_INT_RGB); var bytes=new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image,format,bytes)); image.flush();
        String boundary="Lifecycle"+run; var multipart=new ByteArrayOutputStream();
        multipart.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image."+format+"\"\r\nContent-Type: image/"+format+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(bytes.toByteArray()); multipart.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/media")).timeout(Duration.ofSeconds(20))
                .header("Authorization",author).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray())).build();
        var result=body(client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),201);
        assertEquals(12,result.get("width").asInt()); assertEquals(18,result.get("height").asInt()); return result;
    }
    String register(String suffix,String nickname) throws Exception {
        String username="lc_"+run+suffix; usernames.add(username);
        body(send("POST","/api/auth/register",null,null,json.writeValueAsString(Map.of("username",username,"password",password,"nickname",nickname))),201);
        return login(username);
    }
    String login(String username) throws Exception {
        return "Bearer "+body(send("POST","/api/auth/login",null,null,json.writeValueAsString(Map.of("username",username,"password",password))),200).get("accessToken").asText();
    }
    String draft(String title,String content,List<String> media) throws Exception { return json.writeValueAsString(Map.of("title",title,"content",content,"mediaIds",media)); }
    JsonNode detail(String id,String token) throws Exception { return body(send("GET","/api/posts/"+id,token,null,null),200); }
    boolean contains(JsonNode items,String id) { for (var item:items) if (id.equals(item.path("post_id").asText())) return true; return false; }
    JsonNode find(JsonNode items,String id) { for (var item:items) if (id.equals(item.path("post_id").asText())) return item; fail("Expected post absent: "+id); return null; }
    HttpRequest request(String method,String path,String token,String key,String input) {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if (token!=null) builder.header("Authorization",token);
        if (key!=null) builder.header("Idempotency-Key",key);
        if (input!=null) builder.header("Content-Type","application/json; charset=utf-8");
        return builder.method(method,input==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(input,StandardCharsets.UTF_8)).build();
    }
    HttpResponse<String> send(String method,String path,String token,String key,String input) throws Exception {
        return client.send(request(method,path,token,key,input),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    JsonNode body(HttpResponse<String> response,int status) throws Exception { assertEquals(status,response.statusCode(),response.body()); return json.readTree(response.body()); }
    void error(HttpResponse<String> response,int status,String code) throws Exception { assertEquals(code,body(response,status).get("code").asText()); }

    void cleanup() {
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource()))).executeWithoutResult(tx -> {
            var cleanupOrder=new ArrayList<>(usernames);
            cleanupOrder.remove("admin"); cleanupOrder.add("admin");
            for (String username:cleanupOrder) {
                for (String user:db.queryForList("SELECT id FROM users WHERE username=?",String.class,username)) {
                    for (Long post:db.queryForList("SELECT id FROM posts WHERE author_id=?",Long.class,user)) {
                        db.update("DELETE FROM behavior_events WHERE post_id=?",post);
                        db.update("DELETE FROM moderation_records WHERE post_id=?",post);
                        db.update("DELETE FROM post_media WHERE post_id=?",post);
                        db.update("DELETE FROM post_creation_idempotency WHERE post_id=?",post);
                        db.update("DELETE FROM interactions WHERE target_id=? AND kind IN ('likes','favorites','not-interested')",post.toString());
                        db.update("DELETE FROM posts WHERE id=?",post);
                    }
                    db.update("DELETE FROM sessions WHERE user_id=?",user);
                    db.update("DELETE FROM interactions WHERE user_id=?",user);
                    db.update("DELETE FROM media WHERE owner_id=?",user);
                    db.update("DELETE FROM users WHERE id=?",user);
                    assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users WHERE id=?",Integer.class,user));
                }
            }
        });
    }
}
