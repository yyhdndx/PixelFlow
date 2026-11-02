package com.pixelflow.server.comment;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
public class CommentController {
    public record CreateInput(@NotBlank @Size(max=500) String content) {}
    public record StatusInput(@NotNull @Pattern(regexp="VISIBLE|HIDDEN") String status) {}

    private final JdbcTemplate db;
    private final PostService posts;
    public CommentController(JdbcTemplate db, PostService posts) { this.db=db; this.posts=posts; }

    @PostMapping("/api/posts/{postId}/comments") @Transactional
    public Map<String,Object> create(@PathVariable long postId,@Valid @RequestBody CreateInput input,
            @AuthenticationPrincipal Actor actor) {
        posts.requirePublished(Long.toString(postId));
        String content=input.content().trim();
        if (content.isEmpty() || content.length()>500) throw new ApiException(400,"INVALID_ARGUMENT","评论应为 1–500 字");
        var key=new GeneratedKeyHolder();
        db.update(connection -> {
            var statement=connection.prepareStatement(
                    "INSERT INTO comments(post_id,author_id,content,status,create_time) VALUES(?,?,?,'VISIBLE',?)",
                    new String[]{"comment_id"});
            statement.setLong(1,postId); statement.setString(2,actor.id()); statement.setString(3,content);
            statement.setLong(4,Instant.now().getEpochSecond()); return statement;
        },key);
        long commentId=key.getKey().longValue();
        db.update("UPDATE posts SET comment_count=comment_count+1 WHERE id=?",postId);
        return view(commentId);
    }

    @GetMapping("/api/posts/{postId}/comments")
    public Map<String,Object> list(@PathVariable long postId,@RequestParam(defaultValue="20") int count,
            @RequestParam(required=false) Long cursor) {
        PostService.checkPage(count,cursor);
        if (db.queryForObject("SELECT COUNT(*) FROM posts WHERE id=?",Integer.class,postId)==0)
            throw new ApiException(404,"POST_NOT_FOUND","作品不存在");
        var rows=db.queryForList("SELECT c.*,u.nickname,u.avatar FROM comments c JOIN users u ON c.author_id=u.id "
                + "WHERE c.post_id=? AND c.status='VISIBLE' AND c.comment_id<? ORDER BY c.comment_id DESC LIMIT ?",
                postId,cursor==null ? Long.MAX_VALUE : cursor,count+1);
        boolean more=rows.size()>count;
        var selected=rows.subList(0,Math.min(count,rows.size()));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("status_code",0);
        result.put("comment_list",selected.stream().map(this::view).toList());
        result.put("has_more",more ? 1 : 0);
        result.put("next_cursor",more ? selected.get(selected.size()-1).get("comment_id").toString() : null);
        return result;
    }

    @DeleteMapping("/api/comments/{commentId}") @Transactional
    public Map<String,Object> delete(@PathVariable long commentId,@AuthenticationPrincipal Actor actor) {
        var rows=db.queryForList("SELECT * FROM comments WHERE comment_id=? FOR UPDATE",commentId);
        if (rows.isEmpty()) throw new ApiException(404,"COMMENT_NOT_FOUND","评论不存在");
        var row=rows.get(0);
        if (!actor.id().equals(row.get("author_id")) && !"ADMIN".equals(actor.role()))
            throw new ApiException(403,"FORBIDDEN","只能删除自己的评论");
        String status=(String)row.get("status");
        if (!"DELETED".equals(status)) {
            db.update("UPDATE comments SET status='DELETED' WHERE comment_id=?",commentId);
            if ("VISIBLE".equals(status))
                db.update("UPDATE posts SET comment_count=comment_count-1 WHERE id=?",row.get("post_id"));
        }
        return Map.of("commentId",Long.toString(commentId),"status","DELETED");
    }

    @PutMapping("/api/admin/comments/{commentId}/status") @Transactional
    public Map<String,Object> setStatus(@PathVariable long commentId,@Valid @RequestBody StatusInput input,
            @AuthenticationPrincipal Actor actor) {
        var rows=db.queryForList("SELECT * FROM comments WHERE comment_id=? FOR UPDATE",commentId);
        if (rows.isEmpty()) throw new ApiException(404,"COMMENT_NOT_FOUND","评论不存在");
        var row=rows.get(0);
        String before=(String)row.get("status");
        String after=input.status();
        if ("DELETED".equals(before)) throw new ApiException(409,"INVALID_STATE","已删除评论不能变更状态");
        if (!before.equals(after)) {
            db.update("UPDATE comments SET status=? WHERE comment_id=?",after,commentId);
            if (before.equals("VISIBLE") && after.equals("HIDDEN"))
                db.update("UPDATE posts SET comment_count=comment_count-1 WHERE id=?",row.get("post_id"));
            else if (before.equals("HIDDEN") && after.equals("VISIBLE"))
                db.update("UPDATE posts SET comment_count=comment_count+1 WHERE id=?",row.get("post_id"));
        }
        return Map.of("commentId",Long.toString(commentId),"status",after);
    }

    private Map<String,Object> view(long commentId) {
        var rows=db.queryForList("SELECT c.*,u.nickname,u.avatar FROM comments c JOIN users u ON c.author_id=u.id WHERE c.comment_id=?",commentId);
        if (rows.isEmpty()) throw new ApiException(404,"COMMENT_NOT_FOUND","评论不存在");
        return view(rows.get(0));
    }
    private Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("comment_id",row.get("comment_id").toString());
        result.put("post_id",row.get("post_id").toString());
        result.put("content",row.get("content"));
        result.put("status",row.get("status"));
        result.put("create_time",row.get("create_time"));
        result.put("author",Map.of("user_id",row.get("author_id"),"nickname",row.get("nickname"),
                "avatar",row.getOrDefault("avatar","")));
        return result;
    }
}
