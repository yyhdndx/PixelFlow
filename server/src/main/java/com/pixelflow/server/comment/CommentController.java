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
    public record CreateInput(@NotBlank @Size(max=500) String content,
            @Pattern(regexp="[1-9][0-9]{0,18}") String parentCommentId) {}
    public record StatusInput(@NotNull @Pattern(regexp="VISIBLE|HIDDEN") String status) {}

    private final JdbcTemplate db;
    private final PostService posts;
    private final com.pixelflow.server.notify.NotificationService notifications;
    public CommentController(JdbcTemplate db, PostService posts,
            com.pixelflow.server.notify.NotificationService notifications) {
        this.db=db; this.posts=posts; this.notifications=notifications;
    }

    @PostMapping("/api/posts/{postId}/comments") @Transactional
    public Map<String,Object> create(@PathVariable long postId,@Valid @RequestBody CreateInput input,
            @AuthenticationPrincipal Actor actor) {
        posts.requirePublished(Long.toString(postId));
        String content=input.content().trim();
        if (content.isEmpty() || content.length()>500) throw new ApiException(400,"INVALID_ARGUMENT","评论应为 1–500 字");
        Long parentId=null; String parentAuthor=null;
        if (input.parentCommentId()!=null) {
            parentId=Long.parseLong(input.parentCommentId());
            var parents=db.queryForList("SELECT author_id FROM comments WHERE comment_id=? AND post_id=?",parentId,postId);
            if (parents.isEmpty()) throw new ApiException(404,"COMMENT_NOT_FOUND","被回复的评论不存在");
            parentAuthor=(String)parents.get(0).get("author_id");
        }
        var key=new GeneratedKeyHolder();
        Long parent=parentId;
        db.update(connection -> {
            var statement=connection.prepareStatement(
                    "INSERT INTO comments(post_id,author_id,content,parent_comment_id,status,create_time) VALUES(?,?,?,?,'VISIBLE',?)",
                    new String[]{"comment_id"});
            statement.setLong(1,postId); statement.setString(2,actor.id()); statement.setString(3,content);
            if (parent==null) statement.setNull(4,java.sql.Types.BIGINT); else statement.setLong(4,parent);
            statement.setLong(5,Instant.now().getEpochSecond()); return statement;
        },key);
        long commentId=key.getKey().longValue();
        db.update("UPDATE posts SET comment_count=comment_count+1 WHERE id=?",postId);
        if (parentAuthor!=null)
            notifications.notify(parentAuthor,"REPLY",actor.id(),postId,commentId);
        notifyMentions(content,actor,postId,commentId,parentAuthor);
        return view(commentId);
    }

    /** 解析 @昵称 生成 MENTION 通知；被回复者已由 REPLY 通知则不再重复。 */
    private void notifyMentions(String content,Actor actor,long postId,long commentId,String replyRecipient) {
        var matcher=java.util.regex.Pattern.compile("@([\\p{L}\\p{N}_.-]{1,40})").matcher(content);
        Set<String> nicknames=new LinkedHashSet<>();
        while (matcher.find()) nicknames.add(matcher.group(1));
        Set<String> notified=new HashSet<>();
        for (String nickname : nicknames) {
            var users=db.queryForList("SELECT id FROM users WHERE nickname=? AND enabled=TRUE",nickname);
            for (var user : users) {
                String recipient=(String)user.get("id");
                if (recipient.equals(actor.id()) || recipient.equals(replyRecipient) || !notified.add(recipient)) continue;
                notifications.notify(recipient,"MENTION",actor.id(),postId,commentId);
            }
        }
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
        if (row.get("parent_comment_id") != null) result.put("parent_comment_id",row.get("parent_comment_id").toString());
        result.put("content",row.get("content"));
        result.put("status",row.get("status"));
        result.put("create_time",row.get("create_time"));
        result.put("author",Map.of("user_id",row.get("author_id"),"nickname",row.get("nickname"),
                "avatar",row.getOrDefault("avatar","")));
        return result;
    }
}
