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
        if (!seed || (hasBatch("community-assets-v1") && hasBatch("community-assets-v2")
                && hasBatch("community-assets-v3") && hasBatch("community-assets-v4"))) return;
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
        if (!hasBatch("community-assets-v4")) seedV4(assets);
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
        String[] copy={"一杯手冲，给午后的自己放个假。","刚出炉的面包，配一杯热茶刚刚好。","远处的山和海，值得停下来看一看。","它又霸占了沙发最舒服的位置。","今天的它有点好奇，一直在窗边张望。","角落里的安静时光，最难得。","公园里的花，开得正好。","山间的小瀑布，水声很治愈。"};
        int[] authors={1,2,3,4,4,4,5,6};
        for (int i=0;i<24;i++) {
            int topic=i%files.length;
            JsonNode asset=assets.get(files[topic]);
            boolean video=files[topic].endsWith(".mp4");
            List<PostService.Clip> clips=new ArrayList<>();
            if (video) clips.add(clip(assets.get(files[topic].replace(".mp4","-poster.jpg")),0));
            clips.add(clip(asset,video ? 1 : 0));
            db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,?,'PUBLISHED',?)",
                    "demo-media-author-"+authors[topic],titles[topic],copy[topic],posts.encode(clips),video,Instant.now().getEpochSecond()-i*60);
        }
        db.update("INSERT INTO seed_batches(name,created_at) VALUES('community-assets-v1',?)",Instant.now().getEpochSecond());
    }
    private void seedV2(Map<String,JsonNode> assets) {
        String[][] groups={{"library.jpg"},{"bicycle.jpg"},{"strawberries.jpg"},{"sunset.mp4"},
                {"coffee.jpg","bread.jpg","strawberries.jpg"},{"cat-home.jpg","cat-couch.jpg","cat-standing.jpg"}};
        String[] titles={"图书馆里的阅读时光","街角车轮观察","一盘新鲜草莓","日落延时短片","早餐三图记","猫咪的三个日常角落"};
        String[] copy={"安静的午后，翻开一本书。","街角的自行车，有些年头了。","刚洗好的草莓，很甜。","记录一场完整的日落。","今天的早餐，简单但满足。","它喜欢在三个地方轮流打盹。"};
        int[] authors={5,3,2,3,1,4};
        seedCollection(assets,"community-assets-v2",groups,titles,copy,authors);
    }
    private void seedV3(Map<String,JsonNode> assets) {
        seedCollection(assets,"community-assets-v3",
                new String[][]{{"succulent.jpg"},{"keyboard.jpg"},{"basketball-court.jpg"}},
                new String[]{"多肉植物的绿色纹理","键盘细节特写","晴天的校园篮球场"},
                new String[]{"多肉的纹理，越看越喜欢。","键盘的特写，细节满满。","晴天的球场，很适合打球。"},
                new int[]{5,1,3});
    }
    private void seedV4(Map<String,JsonNode> assets) {
        String[] titles={"今日份的随手拍","生活的小确幸","分享此刻","日常记录","路过的一瞬","随手一拍","留住这一刻",
                "今天的天空","安静的角落","光影之间","周末时光","简单的美好","路过风景","记录当下"};
        String[] copy={"今天天气不错，随手记录一下。","生活里的小确幸，值得收藏。","把这一刻分享给你。","日常的点滴，慢慢攒起来。","路过时随手拍的一张。","简单的一拍，记录当下。","留住这一刻的小美好。","今天的天空很蓝。","安静的角落，让人放松。","光影交错，很好看。","周末的悠闲时光。","简单的小美好。","路过的风景，随手记录。","记录当下的心情。"};
        String[][] groups=new String[14][1];
        int[] authors=new int[14];
        for (int i=0;i<14;i++) { groups[i][0]="moment-"+(i+1)+".jpg"; authors[i]=i%6+1; }
        seedCollection(assets,"community-assets-v4",groups,titles,copy,authors);
    }
    private void seedCollection(Map<String,JsonNode> assets,String batch,
            String[][] groups,String[] titles,String[] copy,int[] authors) {
        for (int i=0;i<groups.length;i++) {
            List<PostService.Clip> clips=new ArrayList<>();
            boolean video=false;
            for (String file:groups[i]) {
                JsonNode asset=assets.get(file);
                boolean isVideo=file.endsWith(".mp4");
                if (isVideo) clips.add(clip(assets.get(file.replace(".mp4","-poster.jpg")),0));
                clips.add(clip(asset,isVideo ? 1 : 0));
                video|=isVideo;
            }
            db.update("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,?,'PUBLISHED',?)",
                    "demo-media-author-"+authors[i],titles[i],copy[i],posts.encode(clips),video,Instant.now().getEpochSecond()-i*60);
        }
        db.update("INSERT INTO seed_batches(name,created_at) VALUES(?,?)",batch,Instant.now().getEpochSecond());
    }
    private String assetUrl(String file) { return baseUrl+"/demo-assets/"+file; }
    private PostService.Clip clip(JsonNode asset,int type) {
        return new PostService.Clip(type,asset.path("width").asInt(),asset.path("height").asInt(),assetUrl(asset.path("file").asText()));
    }
}
