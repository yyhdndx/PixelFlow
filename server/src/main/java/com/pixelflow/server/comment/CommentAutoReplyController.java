package com.pixelflow.server.comment;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
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
 * 作品级「评论自动回复」规则：作者为作品配置「场景 → 答案」。
 * 是否命中场景由 Agent（Python + 模型）判定，命中后以作者身份回一条二级评论。
 * 这里只做规则存储与权限校验，不做关键词匹配。
 */
@RestController
public class CommentAutoReplyController {
    public record RuleInput(@NotBlank @Size(max=200) String question,
            @NotBlank @Size(max=300) String answer) {}
    public record RuleUpdate(@NotBlank @Size(max=200) String question,
            @NotBlank @Size(max=300) String answer, @NotNull Boolean enabled) {}
    public record SuggestInput(@NotBlank @Size(max=500) String prompt) {}
    public record BatchInput(@NotEmpty @Size(max=10) List<@Valid RuleInput> rules) {}

    static final int MAX_RULES_PER_POST = 10;

    private final JdbcTemplate db;
    private final AutoReplySuggester suggester;

    public CommentAutoReplyController(JdbcTemplate db, AutoReplySuggester suggester) {
        this.db = db; this.suggester = suggester;
    }

    @GetMapping("/api/posts/{postId}/auto-replies")
    public Map<String,Object> list(@PathVariable long postId,@AuthenticationPrincipal Actor actor) {
        requireOwner(postId,actor);
        var rules = db.queryForList("SELECT id,question,answer,enabled,create_time,update_time "
                + "FROM comment_auto_replies WHERE post_id=? ORDER BY id",postId);
        return Map.of("status_code",0,"rules",rules.stream().map(this::view).toList());
    }

    @PostMapping("/api/posts/{postId}/auto-replies") @Transactional
    public Map<String,Object> create(@PathVariable long postId,@Valid @RequestBody RuleInput input,
            @AuthenticationPrincipal Actor actor) {
        requireOwner(postId,actor);
        int existing = db.queryForObject("SELECT COUNT(*) FROM comment_auto_replies WHERE post_id=?",
                Integer.class,postId);
        if (existing >= MAX_RULES_PER_POST)
            throw new ApiException(400,"TOO_MANY_RULES","每篇作品最多配置 " + MAX_RULES_PER_POST + " 条自动回复规则");
        long now = Instant.now().getEpochSecond();
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO comment_auto_replies(post_id,owner_id,question,answer,enabled,create_time,update_time)"
                            + " VALUES(?,?,?,?,TRUE,?,?)", new String[]{"id"});
            statement.setLong(1,postId); statement.setString(2,actor.id());
            statement.setString(3,input.question().trim()); statement.setString(4,input.answer().trim());
            statement.setLong(5,now); statement.setLong(6,now);
            return statement;
        },key);
        return view(db.queryForMap("SELECT id,question,answer,enabled,create_time,update_time "
                + "FROM comment_auto_replies WHERE id=?",key.getKey().longValue()));
    }

    @PutMapping("/api/posts/{postId}/auto-replies/{ruleId}") @Transactional
    public Map<String,Object> update(@PathVariable long postId,@PathVariable long ruleId,
            @Valid @RequestBody RuleUpdate input,@AuthenticationPrincipal Actor actor) {
        requireOwner(postId,actor);
        var rows = db.queryForList("SELECT id FROM comment_auto_replies WHERE id=? AND post_id=?",ruleId,postId);
        if (rows.isEmpty()) throw new ApiException(404,"RULE_NOT_FOUND","自动回复规则不存在");
        db.update("UPDATE comment_auto_replies SET question=?,answer=?,enabled=?,update_time=? WHERE id=?",
                input.question().trim(),input.answer().trim(),input.enabled(),
                Instant.now().getEpochSecond(),ruleId);
        return view(db.queryForMap("SELECT id,question,answer,enabled,create_time,update_time "
                + "FROM comment_auto_replies WHERE id=?",ruleId));
    }

    /** 批量创建（AI 候选多选后「应用」用）：一次最多 10 条，与总量上限一起校验。 */
    @PostMapping("/api/posts/{postId}/auto-replies/batch") @Transactional
    public Map<String,Object> batch(@PathVariable long postId,@Valid @RequestBody BatchInput input,
            @AuthenticationPrincipal Actor actor) {
        requireOwner(postId,actor);
        int existing = db.queryForObject("SELECT COUNT(*) FROM comment_auto_replies WHERE post_id=?",
                Integer.class,postId);
        if (existing + input.rules().size() > MAX_RULES_PER_POST)
            throw new ApiException(400,"TOO_MANY_RULES",
                    "每篇作品最多 " + MAX_RULES_PER_POST + " 条规则，当前已有 " + existing + " 条");
        var created = new ArrayList<Map<String,Object>>();
        long now = Instant.now().getEpochSecond();
        for (RuleInput rule : input.rules()) {
            var key = new GeneratedKeyHolder();
            db.update(connection -> {
                var statement = connection.prepareStatement(
                        "INSERT INTO comment_auto_replies(post_id,owner_id,question,answer,enabled,create_time,update_time)"
                                + " VALUES(?,?,?,?,TRUE,?,?)", new String[]{"id"});
                statement.setLong(1,postId); statement.setString(2,actor.id());
                statement.setString(3,rule.question().trim()); statement.setString(4,rule.answer().trim());
                statement.setLong(5,now); statement.setLong(6,now);
                return statement;
            },key);
            created.add(view(db.queryForMap("SELECT id,question,answer,enabled,create_time,update_time "
                    + "FROM comment_auto_replies WHERE id=?",key.getKey().longValue())));
        }
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("status_code",0);
        response.put("created",created);
        return response;
    }
    /** 用 Agent 按作品上下文把作者描述拆成若干「场景 → 答案」候选。 */
    @PostMapping("/api/posts/{postId}/auto-replies/suggest") @Transactional(readOnly = true)
    public Map<String,Object> suggest(@PathVariable long postId,@Valid @RequestBody SuggestInput input,
            @AuthenticationPrincipal Actor actor) {
        requireOwner(postId,actor);
        var posts = db.queryForList("SELECT title,content FROM posts WHERE id=?",postId);
        if (posts.isEmpty()) throw new ApiException(404,"POST_NOT_FOUND","作品不存在");
        var post = posts.get(0);
        var result = suggester.suggest(postId,String.valueOf(post.get("title")),
                String.valueOf(post.get("content")),input.prompt().trim());
        if (result.isEmpty()) throw new ApiException(503,"AI_UNAVAILABLE","AI 提炼暂不可用，请手动填写场景和回复");
        var rules = result.get().rules().stream()
                .map(rule -> Map.of("question",rule.question(),"answer",rule.answer()))
                .toList();
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("status_code",0);
        response.put("suggestions",rules);
        response.put("degraded",result.get().degraded());
        response.put("reason",result.get().reason());
        return response;
    }

    @DeleteMapping("/api/posts/{postId}/auto-replies/{ruleId}") @Transactional
    public Map<String,Object> delete(@PathVariable long postId,@PathVariable long ruleId,
            @AuthenticationPrincipal Actor actor) {
        requireOwner(postId,actor);
        int removed = db.update("DELETE FROM comment_auto_replies WHERE id=? AND post_id=?",ruleId,postId);
        if (removed == 0) throw new ApiException(404,"RULE_NOT_FOUND","自动回复规则不存在");
        return Map.of("ruleId",Long.toString(ruleId),"deleted",true);
    }

    /** 只允许作品作者本人（管理员可代管）配置。 */
    private void requireOwner(long postId,Actor actor) {
        var owners = db.queryForList("SELECT author_id FROM posts WHERE id=?",String.class,postId);
        if (owners.isEmpty()) throw new ApiException(404,"POST_NOT_FOUND","作品不存在");
        boolean admin = "ADMIN".equals(actor.role());
        if (!admin && !owners.get(0).equals(actor.id()))
            throw new ApiException(403,"FORBIDDEN","只能配置自己作品的自动回复");
    }

    private Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id",row.get("id").toString());
        result.put("question",row.get("question"));
        result.put("answer",row.get("answer"));
        result.put("enabled",Boolean.TRUE.equals(row.get("enabled")));
        result.put("create_time",row.get("create_time"));
        result.put("update_time",row.get("update_time"));
        return result;
    }
}
