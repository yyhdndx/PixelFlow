package com.pixelflow.server.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class PostService {
    public record Clip(@Min(0) @Max(1) int type, @Min(1) @Max(16384) int width,
            @Min(1) @Max(16384) int height,
            @NotBlank @Size(max=2048) @Pattern(regexp="https?://[^\\s]+") String url) {}
    public record Draft(@NotNull @Size(max=120) String title, @NotNull @Size(max=5000) String content,
            @Size(max=9) List<@NotNull @Valid Clip> clips,
            @Size(max=9) List<@NotBlank @Size(max=36) String> mediaIds) {
        public Draft {
            clips=clips==null ? List.of() : clips;
            mediaIds=mediaIds==null ? List.of() : mediaIds;
        }
    }
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final MediaService media;
    public PostService(JdbcTemplate db,ObjectMapper json,MediaService media) { this.db=db; this.json=json; this.media=media; }

    private List<Clip> resolve(Actor actor,Draft draft) {
        if (!draft.clips().isEmpty() && !draft.mediaIds().isEmpty())
            throw new ApiException(400,"INVALID_ARGUMENT","clips 与 mediaIds 不能同时提供");
        if (!draft.mediaIds().isEmpty()) return media.ownedClips(actor,draft.mediaIds());
        for (Clip clip:draft.clips()) {
            try {
                var uri=java.net.URI.create(clip.url());
                if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getPath().contains("/api/media/"))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new ApiException(400,"INVALID_MEDIA","媒体 URL 无效；本站上传文件请使用 mediaIds");
            }
        }
        return draft.clips();
    }

    @Transactional
    public Map<String,Object> create(Actor actor,Draft draft) {
        media.lockOwner(actor);
        var clips=resolve(actor,draft);
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO posts(author_id,title,content,clips_json,has_video,status,create_time) VALUES(?,?,?,?,?,'DRAFT',?)",new String[]{"id"});
            statement.setString(1,actor.id()); statement.setString(2,draft.title()); statement.setString(3,draft.content());
            statement.setString(4,encode(clips)); statement.setBoolean(5,clips.stream().anyMatch(c -> c.type()==1));
            statement.setLong(6,Instant.now().getEpochSecond()); return statement;
        },key);
        long id=key.getKey().longValue();
        media.attach(id,draft.mediaIds());
        return detail(id,actor);
    }

    @Transactional
    public Map<String,Object> edit(long id,Actor actor,Draft draft) {
        media.lockOwner(actor);
        var post = owned(id,actor);
        if (!List.of("DRAFT","REJECTED","WITHDRAWN").contains(post.get("status")))
            throw new ApiException(409,"INVALID_STATE","请先下架作品再编辑");
        var clips=resolve(actor,draft);
        db.update("UPDATE posts SET title=?,content=?,clips_json=?,has_video=?,status='DRAFT' WHERE id=?",
                draft.title(),draft.content(),encode(clips),clips.stream().anyMatch(c -> c.type()==1),id);
        media.attach(id,draft.mediaIds());
        return detail(id,actor);
    }

    @Transactional
    public Map<String,Object> transition(long id,Actor actor,boolean publish) {
        var post=owned(id,actor);
        String state=(String)post.get("status");
        String next=publish ? "PENDING" : "WITHDRAWN";
        if (publish && (((String)post.get("title")).isBlank() || "[]".equals(post.get("clips_json"))))
            throw new ApiException(409,"INCOMPLETE_DRAFT","发布前请填写标题并添加至少一个媒体");
        if (publish && !List.of("DRAFT","REJECTED","WITHDRAWN","PENDING").contains(state))
            throw new ApiException(409,"INVALID_STATE","当前状态不能提交审核");
        db.update("UPDATE posts SET status=? WHERE id=?",next,id);
        return Map.of("postId",Long.toString(id),"status",next);
    }

    private Map<String,Object> owned(long id,Actor actor) {
        var rows=db.queryForList("SELECT * FROM posts WHERE id=? FOR UPDATE",id);
        if (rows.isEmpty()) throw missing();
        var row=rows.get(0);
        if (!actor.id().equals(row.get("author_id"))) throw new ApiException(403,"FORBIDDEN","只能修改自己的作品");
        return row;
    }

    public Map<String,Object> detail(long id,Actor actor) {
        var rows=db.queryForList("SELECT p.*,u.nickname,u.avatar,u.enabled FROM posts p JOIN users u ON p.author_id=u.id WHERE p.id=?",id);
        if (rows.isEmpty()) throw missing();
        var row=rows.get(0);
        if ((!"PUBLISHED".equals(row.get("status")) || !Boolean.TRUE.equals(row.get("enabled")))
                && (actor==null || (!actor.id().equals(row.get("author_id")) && !actor.role().equals("ADMIN")))) throw missing();
        return withLikeState(view(row),actor);
    }

    public Map<String,Object> feed(int count,Long cursor,boolean video) {
        return feed(count,cursor,video,null);
    }
    public Map<String,Object> feed(int count,Long cursor,boolean video,Actor actor) {
        checkPage(count,cursor);
        var rows=db.queryForList("SELECT p.*,u.nickname,u.avatar FROM posts p JOIN users u ON p.author_id=u.id "
                + "WHERE p.status='PUBLISHED' AND u.enabled=TRUE AND p.id<? "
                + (video ? "" : "AND p.has_video=FALSE ") + "ORDER BY p.id DESC LIMIT ?",
                cursor==null ? Long.MAX_VALUE : cursor,count+1);
        boolean more=rows.size()>count;
        var selected=rows.subList(0,Math.min(count,rows.size()));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("status_code",0); result.put("has_more",more ? 1 : 0);
        result.put("post_list",selected.stream().map(row -> withLikeState(view(row),actor)).toList());
        result.put("next_cursor",more ? selected.get(selected.size()-1).get("id").toString() : null);
        return result;
    }

    public List<Map<String,Object>> mine(Actor actor,int count,Long cursor) {
        checkPage(count,cursor);
        return db.queryForList("SELECT p.*,u.nickname,u.avatar FROM posts p JOIN users u ON p.author_id=u.id WHERE p.author_id=? AND p.id<? ORDER BY p.id DESC LIMIT ?",
                actor.id(),cursor==null ? Long.MAX_VALUE : cursor,count).stream().map(this::view).toList();
    }
    private Map<String,Object> withLikeState(Map<String,Object> post,Actor actor) {
        post.put("is_liked",actor!=null && db.queryForObject(
                "SELECT COUNT(*) FROM interactions WHERE user_id=? AND kind='likes' AND target_id=?",
                Integer.class,actor.id(),post.get("post_id"))>0);
        return post;
    }
    public static void checkPage(int count,Long cursor) {
        if (count<1 || count>50 || (cursor!=null && cursor<1)) throw new ApiException(400,"INVALID_ARGUMENT","count 应为 1–50，cursor 应为正整数");
    }
    public void requirePublished(String id) {
        if (!id.matches("[1-9][0-9]{0,17}") || db.queryForObject("SELECT COUNT(*) FROM posts p JOIN users u ON p.author_id=u.id WHERE p.id=? AND p.status='PUBLISHED' AND u.enabled=TRUE",Integer.class,Long.parseLong(id))==0) throw missing();
    }
    public Map<String,Object> view(Map<String,Object> row) {
        String id=row.get("id").toString();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("post_id",id); result.put("title",row.get("title")); result.put("content",row.get("content"));
        result.put("create_time",row.get("create_time")); result.put("status",row.get("status"));
        result.put("media_ids",db.queryForList("SELECT media_id FROM post_media WHERE post_id=? ORDER BY position",String.class,Long.parseLong(id)));
        result.put("author",Map.of("user_id",row.get("author_id"),"nickname",row.get("nickname"),"avatar",row.getOrDefault("avatar","")));
        try { result.put("clips",json.readValue((String)row.get("clips_json"),new TypeReference<List<Clip>>(){})); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored media metadata",e); }
        result.put("hashtag",List.of()); result.put("music",null);
        result.put("like_count",db.queryForObject("SELECT COUNT(*) FROM interactions WHERE kind='likes' AND target_id=?",Long.class,id));
        return result;
    }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid media metadata",e); }
    }
    private static ApiException missing() { return new ApiException(404,"POST_NOT_FOUND","作品不存在或不可见"); }
}
