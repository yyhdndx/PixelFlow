package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

@Component
public class DemoData implements ApplicationRunner {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final PostService posts;
    private final boolean seed;
    private final String adminPassword;
    private final String baseUrl;
    private final ObjectMapper json;
    public DemoData(JdbcTemplate db,AuthService auth,PostService posts,@Value("${pixelflow.seed}") boolean seed,
            @Value("${pixelflow.admin-password}") String adminPassword,
            @Value("${pixelflow.public-base-url}") String baseUrl,ObjectMapper json) {
        this.db=db; this.auth=auth; this.posts=posts; this.seed=seed; this.adminPassword=adminPassword;
        this.baseUrl=baseUrl.replaceAll("/+$",""); this.json=json;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (!adminPassword.isBlank() && db.queryForObject("SELECT COUNT(*) FROM users WHERE username='admin'",Integer.class)==0)
            db.update("INSERT INTO users(id,username,password_hash,nickname,role) VALUES(?,?,?,?,?)",
                    UUID.randomUUID().toString(),"admin",auth.passwordHash(adminPassword),"管理员","ADMIN");
        if (!seed || (hasBatch("community-assets-v1") && hasBatch("community-assets-v2") && hasBatch("community-assets-v3"))) return;
        Map<String,JsonNode> assets=new HashMap<>();
        try (var input=new ClassPathResource("static/demo-assets/catalog.json").getInputStream()) {
            for (JsonNode asset:json.readTree(input).path("assets")) {
                String file=asset.path("file").asText();
                if (!file.matches("[a-z0-9-]+\\.(jpg|png|mp4)") || !new ClassPathResource("static/demo-assets/"+file).exists())
                    throw new IllegalStateException("Missing bundled demo resource: "+file);
                assets.put(file,asset);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot load bundled demo catalog",e); }
        if (!hasBatch("community-assets-v1")) seedV1(assets);
        if (!hasBatch("community-assets-v2")) seedV2(assets);
        if (!hasBatch("community-assets-v3")) seedV3(assets);
    }
    private boolean hasBatch(String name) {
        return db.queryForObject("SELECT COUNT(*) FROM seed_batches WHERE name=?",Integer.class,name)>0;
    }
    private void seedV1(Map<String,JsonNode> assets) {
        String[] names={"咖啡角","烘焙手记","慢慢旅行","猫咪观察员","公园散步","山间记录"};
        for (int i=1;i<=6;i++) {
            db.update("INSERT INTO users(id,username,password_hash,nickname,role,avatar) VALUES(?,?,?,?,?,?)",
                    "demo-media-author-"+i,"demo_media_"+i,auth.passwordHash(UUID.randomUUID().toString()),
                    names[i-1]+"（演示）","CREATOR",assetUrl("avatar-"+i+".png"));
        }
        String[] files={"coffee.jpg","bread.jpg","oahu.jpg","cat-couch.jpg","cat-standing.jpg","cat-home.jpg","flowers.mp4","waterfall.mp4"};
        String[] titles={"给午后留一杯咖啡","面包的日常","山海之间的风景","沙发上的猫咪","今天的猫咪观察","家里的安静角落","公园花丛短片","山间瀑布短片"};
        int[] authors={1,2,3,4,4,4,5,6};
        for (int i=0;i<24;i++) {
            int topic=i%files.length;
            JsonNode asset=assets.get(files[topic]);
            boolean video=files[topic].endsWith(".mp4");
            List<PostService.Clip> clips=new ArrayList<>();
            if (video) clips.add(clip(assets.get(files[topic].replace(".mp4","-poster.jpg")),0));
            clips.add(clip(asset,video ? 1 : 0));
            String credit="【课程演示素材，第 "+(i/8+1)+" 组】\n素材作者："+asset.path("author").asText()
                    +"；许可："+asset.path("license").asText()+"\n来源："+asset.path("source").asText()
                    +"\n许可链接："+asset.path("licenseUrl").asText()
                    +"\n处理："+asset.path("changes").asText()+"\n本账号为虚构演示身份，素材作者不代表社区用户。完整署名："+assetUrl("CREDITS.txt");
            db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,?,'PUBLISHED',?)",
                    "demo-media-author-"+authors[topic],titles[topic]+" · "+(i/8+1),credit,posts.encode(clips),video,Instant.now().getEpochSecond()-i*60);
        }
        db.update("INSERT INTO seed_batches(name,created_at) VALUES('community-assets-v1',?)",Instant.now().getEpochSecond());
    }
    private void seedV2(Map<String,JsonNode> assets) {
        String[][] groups={{"library.jpg"},{"bicycle.jpg"},{"strawberries.jpg"},{"sunset.mp4"},
                {"coffee.jpg","bread.jpg","strawberries.jpg"},{"cat-home.jpg","cat-couch.jpg","cat-standing.jpg"}};
        String[] titles={"图书馆里的阅读时光","街角车轮观察","一盘新鲜草莓","日落延时短片","早餐三图记","猫咪的三个日常角落"};
        int[] authors={5,3,2,3,1,4};
        seedCollection(assets,"community-assets-v2","扩展素材 · ",groups,titles,authors);
    }
    private void seedV3(Map<String,JsonNode> assets) {
        seedCollection(assets,"community-assets-v3","生活素材 · ",
                new String[][]{{"succulent.jpg"},{"keyboard.jpg"},{"basketball-court.jpg"}},
                new String[]{"多肉植物的绿色纹理","键盘细节特写","晴天的校园篮球场"},new int[]{5,1,3});
    }
    private void seedCollection(Map<String,JsonNode> assets,String batch,String titlePrefix,
            String[][] groups,String[] titles,int[] authors) {
        for (int i=0;i<groups.length;i++) {
            List<PostService.Clip> clips=new ArrayList<>();
            StringBuilder content=new StringBuilder("【课程演示素材扩展】本账号为虚构身份，素材作者不代表社区用户。\n");
            boolean video=false;
            for (String file:groups[i]) {
                JsonNode asset=assets.get(file);
                boolean isVideo=file.endsWith(".mp4");
                if (isVideo) clips.add(clip(assets.get(file.replace(".mp4","-poster.jpg")),0));
                clips.add(clip(asset,isVideo ? 1 : 0));
                video|=isVideo;
                content.append("\n素材：").append(file).append("；作者：").append(asset.path("author").asText())
                        .append("；许可：").append(asset.path("license").asText())
                        .append("\n来源：").append(asset.path("source").asText())
                        .append("\n许可链接：").append(asset.path("licenseUrl").asText())
                        .append("\n处理：").append(asset.path("changes").asText()).append("\n");
            }
            content.append("完整署名：").append(assetUrl("CREDITS.txt"));
            db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,?,'PUBLISHED',?)",
                    "demo-media-author-"+authors[i],titlePrefix+titles[i],content.toString(),posts.encode(clips),video,Instant.now().getEpochSecond()-i*60);
        }
        db.update("INSERT INTO seed_batches(name,created_at) VALUES(?,?)",batch,Instant.now().getEpochSecond());
    }
    private String assetUrl(String file) { return baseUrl+"/demo-assets/"+file; }
    private PostService.Clip clip(JsonNode asset,int type) {
        return new PostService.Clip(type,asset.path("width").asInt(),asset.path("height").asInt(),assetUrl(asset.path("file").asText()));
    }
}
