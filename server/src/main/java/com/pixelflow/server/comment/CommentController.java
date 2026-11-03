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

/**
 * 两级评论区：一级评论平铺，回复统一挂在所属一级评论下（parent_comment_id = 一级评论 id），
 * reply_to_user_id 记录“回复了谁”，用于渲染“回复 @某人”。回复别人的回复不会再加深层级。
 */
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
        Long parentId=null; String replyToUser=null;
        if (input.parentCommentId()!=null) {
            long target=Long.parseLong(input.parentCommentId());
            var targets=db.queryForList("SELECT comment_id,author_id,parent_comment_id FROM comments "
                    + "WHERE comment_id=? AND post_id=? AND status<>'DELETED'",target,postId);
            if (targets.isEmpty()) throw new ApiException(404,"COMMENT_NOT_FOUND","被回复的评论不存在");
            var targetRow=targets.get(0);
            Object targetParent=targetRow.get("parent_comment_id");
            // 回复一级评论 → 父就是它；回复某条回复 → 仍挂到同一条一级评论下
            parentId=targetParent==null ? target : ((Number)targetParent).longValue();
            replyToUser=(String)targetRow.get("author_id");
        }
        var key=new GeneratedKeyHolder();
        Long parent=parentId; String replyTo=replyToUser;
        db.update(connection -> {
            var statement=connection.prepareStatement(
                    "INSERT INTO comments(post_id,author_id,content,parent_comment_id,reply_to_user_id,status,create_time)"
                            + " VALUES(?,?,?,?,?,'VISIBLE',?)",
                    new String[]{"comment_id"});
            statement.setLong(1,postId); statement.setString(2,actor.id()); statement.setString(3,content);
            if (parent==null) statement.setNull(4,java.sql.Types.BIGINT); else statement.setLong(4,parent);
            if (replyTo==null) statement.setNull(5,java.sql.Types.VARCHAR); else statement.setString(5,replyTo);
            statement.setLong(6,Instant.now().getEpochSecond()); return statement;
        },key);
        long commentId=key.getKey().longValue();
        db.update("UPDATE posts SET comment_count=comment_count+1 WHERE id=?",postId);
        if (replyToUser!=null)
            notifications.notify(replyToUser,"REPLY",actor.id(),postId,commentId);
        notifyMentions(content,actor,postId,commentId,replyToUser);
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

    /** 一级评论分页（ID 倒序），每条带 replies（按时间正序）。游标是一级评论 id。 */
    @GetMapping("/api/posts/{postId}/comments")
    public Map<String,Object> list(@PathVariable long postId,@RequestParam(defaultValue="20") int count,
            @RequestParam(required=false) Long cursor) {
        PostService.checkPage(count,cursor);
        if (db.queryForObject("SELECT COUNT(*) FROM posts WHERE id=?",Integer.class,postId)==0)
            throw new ApiException(404,"POST_NOT_FOUND","作品不存在");
        var roots=db.queryForList("SELECT c.*,u.nickname,u.avatar FROM comments c JOIN users u ON c.author_id=u.id "
                + "WHERE c.post_id=? AND c.parent_comment_id IS NULL AND c.status='VISIBLE' AND c.comment_id<? "
                + "ORDER BY c.comment_id DESC LIMIT ?",
                postId,cursor==null ? Long.MAX_VALUE : cursor,count+1);
        boolean more=roots.size()>count;
        var selected=roots.subList(0,Math.min(count,roots.size()));
        Map<Long,List<Map<String,Object>>> repliesByRoot=loadReplies(selected);
        var commentList=new ArrayList<Map<String,Object>>(selected.size());
        for (var root : selected) {
            Map<String,Object> item=view(root);
            List<Map<String,Object>> replies=repliesByRoot.getOrDefault(number(root.get("comment_id")),List.of());
            item.put("reply_count",replies.size());
            item.put("replies",replies);
            commentList.add(item);
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("status_code",0);
        result.put("comment_list",commentList);
        result.put("has_more",more ? 1 : 0);
        result.put("next_cursor",more ? selected.get(selected.size()-1).get("comment_id").toString() : null);
        return result;
    }

    /** 批量取这些一级评论下的可见回复；root_comment_id 兼容历史上“回复的回复”存成直接父评论的情况。 */
    private Map<Long,List<Map<String,Object>>> loadReplies(List<Map<String,Object>> roots) {
        Map<Long,List<Map<String,Object>>> byRoot=new LinkedHashMap<>();
        if (roots.isEmpty()) return byRoot;
        List<Long> ids=new ArrayList<>(roots.size());
        for (var root : roots) ids.add(number(root.get("comment_id")));
        String in=String.join(",",Collections.nCopies(ids.size(),"?"));
        var replies=db.queryForList("SELECT c.*,u.nickname,u.avatar,COALESCE(rt.nickname,pu.nickname) AS parent_nickname,"
                + " COALESCE(p.parent_comment_id,c.parent_comment_id) AS root_comment_id "
                + "FROM comments c JOIN users u ON c.author_id=u.id "
                + "LEFT JOIN comments p ON c.parent_comment_id=p.comment_id "
                + "LEFT JOIN users pu ON p.author_id=pu.id "
                + "LEFT JOIN users rt ON c.reply_to_user_id=rt.id "
                + "WHERE c.parent_comment_id IS NOT NULL AND c.status='VISIBLE' "
                + "AND COALESCE(p.parent_comment_id,c.parent_comment_id) IN ("+in+") "
                + "ORDER BY c.comment_id ASC",
                ids.toArray());
        for (var row : replies) {
            long rootId=number(row.get("root_comment_id"));
            byRoot.computeIfAbsent(rootId,k -> new ArrayList<>()).add(view(row));
        }
        return byRoot;
    }

    /** 删除自己的评论；一级评论会被一起级联删除，并同步减少作品评论数。 */
    @DeleteMapping("/api/comments/{commentId}") @Transactional
    public Map<String,Object> delete(@PathVariable long commentId,@AuthenticationPrincipal Actor actor) {
        var rows=db.queryForList("SELECT * FROM comments WHERE comment_id=? FOR UPDATE",commentId);
        if (rows.isEmpty()) throw new ApiException(404,"COMMENT_NOT_FOUND","评论不存在");
        var row=rows.get(0);
        if (!actor.id().equals(row.get("author_id")) && !"ADMIN".equals(actor.role()))
            throw new ApiException(403,"FORBIDDEN","只能删除自己的评论");
        long postId=number(row.get("post_id"));
        List<Long> scope=threadScope(commentId);
        int removed=countIn("VISIBLE",scope);
        if (removed>0) {
            db.update("UPDATE comments SET status='DELETED' WHERE status='VISIBLE' AND comment_id IN ("
                    + placeholders(scope.size())+")",scope.toArray());
            db.update("UPDATE posts SET comment_count=comment_count-? WHERE id=?",removed,postId);
        }
        return Map.of("commentId",Long.toString(commentId),"status","DELETED","removed",removed);
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
        long postId=number(row.get("post_id"));
        List<Long> scope=threadScope(commentId);
        String from="VISIBLE".equals(after) ? "HIDDEN" : "VISIBLE";
        int affected=countIn(from,scope);
        if (affected>0) {
            db.update("UPDATE comments SET status=? WHERE status=? AND comment_id IN ("
                    + placeholders(scope.size())+")",concat(new Object[]{after,from},scope.toArray()));
            int delta="VISIBLE".equals(after) ? affected : -affected;
            db.update("UPDATE posts SET comment_count=comment_count+? WHERE id=?",delta,postId);
        }
        return Map.of("commentId",Long.toString(commentId),"status",after);
    }

    /** 一级评论 + 它的全部回复；回复只有自身。 */
    private List<Long> threadScope(long commentId) {
        List<Long> ids=new ArrayList<>();
        ids.add(commentId);
        ids.addAll(db.queryForList("SELECT comment_id FROM comments WHERE parent_comment_id=?",Long.class,commentId));
        return ids;
    }

    private int countIn(String status,List<Long> ids) {
        return db.queryForObject("SELECT COUNT(*) FROM comments WHERE status=? AND comment_id IN ("
                + placeholders(ids.size())+")",Integer.class,concat(new Object[]{status},ids.toArray()));
    }

    private static String placeholders(int n) { return String.join(",",Collections.nCopies(n,"?")); }

    private static Object[] concat(Object[] head,Object[] tail) {
        Object[] all=new Object[head.length+tail.length];
        System.arraycopy(head,0,all,0,head.length);
        System.arraycopy(tail,0,all,head.length,tail.length);
        return all;
    }

    private static long number(Object value) { return ((Number)value).longValue(); }

    private Map<String,Object> view(long commentId) {
        var rows=db.queryForList("SELECT c.*,u.nickname,u.avatar,COALESCE(rt.nickname,pu.nickname) AS parent_nickname,"
                + " COALESCE(p.parent_comment_id,c.parent_comment_id) AS root_comment_id "
                + "FROM comments c JOIN users u ON c.author_id=u.id "
                + "LEFT JOIN comments p ON c.parent_comment_id=p.comment_id "
                + "LEFT JOIN users pu ON p.author_id=pu.id "
                + "LEFT JOIN users rt ON c.reply_to_user_id=rt.id WHERE c.comment_id=?",commentId);
        if (rows.isEmpty()) throw new ApiException(404,"COMMENT_NOT_FOUND","评论不存在");
        return view(rows.get(0));
    }

    private Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("comment_id",row.get("comment_id").toString());
        result.put("post_id",row.get("post_id").toString());
        if (row.get("parent_comment_id") != null) result.put("parent_comment_id",row.get("parent_comment_id").toString());
        if (row.get("root_comment_id") != null) result.put("root_comment_id",row.get("root_comment_id").toString());
        if (row.get("parent_nickname") != null) result.put("parent_nickname",row.get("parent_nickname"));
        result.put("content",row.get("content"));
        result.put("status",row.get("status"));
        result.put("create_time",row.get("create_time"));
        result.put("author",Map.of("user_id",row.get("author_id"),"nickname",row.get("nickname"),
                "avatar",row.getOrDefault("avatar","")));
        return result;
    }
}
