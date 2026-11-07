package com.pixelflow.server.copilot;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 应用内 Copilot 网关：客户端只上报「页面 + 实体 ID + 用户这句话」，
 * 上下文由服务端按当前用户权限重新读取，不信任客户端传来的正文。
 * 模型只返回提案，实际写入仍走各自的业务接口（评论、草稿、批量私信）。
 */
@RestController
public class CopilotController {
    static final int MAX_HISTORY = 20;
    static final int MAX_CONVERSATIONS = 10;
    static final int MAX_COMMENT = 500;
    static final int MAX_MESSAGE = 1000;

    public record HistoryItem(@Pattern(regexp="user|assistant") String role,
                              @NotBlank @Size(max=500) String content) {}
    public record Request(@NotBlank @Pattern(regexp="detail|create|messages") String screen,
                          @Pattern(regexp="[1-9][0-9]{0,18}") String postId,
                          @Pattern(regexp="[1-9][0-9]{0,18}") String draftId,
                          @Size(max=MAX_CONVERSATIONS) List<@Pattern(regexp="[1-9][0-9]{0,18}") String> conversationIds,
                          @Size(max=500) String selection,
                          @NotBlank @Size(max=2000) String message,
                          @Size(max=MAX_HISTORY) List<@Valid HistoryItem> history) {}

    private final JdbcTemplate db;
    private final CopilotAdvisor advisor;

    public CopilotController(JdbcTemplate db, CopilotAdvisor advisor) {
        this.db = db; this.advisor = advisor;
    }

    @PostMapping("/api/agent/copilot")
    public Map<String,Object> copilot(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Request input) {
        String screen = input.screen();
        Map<String,Object> context;
        Set<String> allowedConversations = Set.of();
        if ("detail".equals(screen)) {
            if (input.postId() == null) throw new ApiException(400,"INVALID_ARGUMENT","detail 需要 postId");
            context = detailContext(actor, input.postId());
        } else if ("create".equals(screen)) {
            if (input.draftId() == null) throw new ApiException(400,"INVALID_ARGUMENT","create 需要 draftId");
            context = draftContext(actor, input.draftId());
        } else {
            var ids = input.conversationIds() == null ? List.<String>of() : input.conversationIds();
            if (ids.isEmpty()) throw new ApiException(400,"INVALID_ARGUMENT","messages 需要 conversationIds");
            allowedConversations = new LinkedHashSet<>(ids);
            context = conversationsContext(actor, ids);
        }

        var history = new ArrayList<Map<String,Object>>();
        for (var item : input.history() == null ? List.<HistoryItem>of() : input.history())
            history.add(Map.of("role", item.role(), "content", item.content()));

        var result = advisor.chat(screen, context, input.message(), history);
        if (result.isEmpty())
            throw new ApiException(503,"AI_UNAVAILABLE","AI 暂时不可用，请稍后再试");

        List<Map<String,Object>> actions = validate(screen, result.get().actions(), context, allowedConversations);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("status_code", 0);
        response.put("reply", result.get().reply());
        response.put("actions", actions);
        response.put("screen", screen);
        response.put("trace_id", result.get().traceId());
        return response;
    }

    /** 二次校验：即使模型或 Python 端出错，也不让越界动作离开网关。 */
    private List<Map<String,Object>> validate(String screen, List<Map<String,Object>> actions,
                                              Map<String,Object> context, Set<String> allowedConversations) {
        List<Map<String,Object>> kept = new ArrayList<>();
        for (Map<String,Object> action : actions == null ? List.<Map<String,Object>>of() : actions) {
            String type = String.valueOf(action.getOrDefault("type","")).toUpperCase(Locale.ROOT);
            String rationale = string(action.get("rationale"));
            if ("detail".equals(screen) && "POST_COMMENT".equals(type)) {
                String text = string(action.get("text"));
                if (text.isEmpty() || text.length() > MAX_COMMENT) continue;
                Map<String,Object> item = new LinkedHashMap<>();
                item.put("type","POST_COMMENT"); item.put("text",text); item.put("rationale",rationale);
                kept.add(item);
            } else if ("create".equals(screen) && "UPDATE_DRAFT".equals(type)) {
                String content = string(action.get("content"));
                String title = string(action.get("title"));
                if (content.isEmpty() && title.isEmpty()) continue;
                if (content.length() > 5000 || title.length() > 120) continue;
                Map<String,Object> item = new LinkedHashMap<>();
                item.put("type","UPDATE_DRAFT"); item.put("content",content);
                if (!title.isEmpty()) item.put("title",title);
                item.put("rationale",rationale);
                kept.add(item);
            } else if ("messages".equals(screen) && "SEND_MESSAGES".equals(type)) {
                List<Map<String,Object>> items = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                Object raw = action.get("items");
                if (raw instanceof List<?> list) {
                    for (Object entry : list) {
                        if (!(entry instanceof Map<?,?> map)) continue;
                        String conversation = string(map.get("conversation_id"));
                        String text = string(map.get("text"));
                        if (!allowedConversations.contains(conversation) || !seen.add(conversation)) continue;
                        if (text.isEmpty() || text.length() > MAX_MESSAGE) continue;
                        Map<String,Object> item = new LinkedHashMap<>();
                        item.put("conversation_id",conversation); item.put("text",text);
                        items.add(item);
                        if (items.size() >= 10) break;
                    }
                }
                if (!items.isEmpty()) {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("type","SEND_MESSAGES"); item.put("items",items); item.put("rationale",rationale);
                    kept.add(item);
                }
            }
        }
        return kept;
    }

    private Map<String,Object> detailContext(Actor actor, String postId) {
        var rows = db.queryForList("SELECT id,author_id,title,content,status FROM posts WHERE id=?",
                Long.parseLong(postId));
        if (rows.isEmpty()) throw new ApiException(404,"POST_NOT_FOUND","作品不存在");
        var row = rows.get(0);
        boolean owner = actor != null && actor.id().equals(String.valueOf(row.get("author_id")));
        if (!"PUBLISHED".equals(row.get("status")) && !owner)
            throw new ApiException(404,"POST_NOT_FOUND","作品不存在");
        Map<String,Object> post = new LinkedHashMap<>();
        post.put("id",String.valueOf(row.get("id")));
        post.put("title",row.get("title"));
        post.put("content",row.get("content"));
        post.put("author",row.get("author_id"));
        return Map.of("post", post);
    }

    private Map<String,Object> draftContext(Actor actor, String draftId) {
        var rows = db.queryForList("SELECT id,author_id,title,content,content_version FROM posts WHERE id=?",
                Long.parseLong(draftId));
        if (rows.isEmpty()) throw new ApiException(404,"POST_NOT_FOUND","草稿不存在");
        var row = rows.get(0);
        if (actor == null || !actor.id().equals(String.valueOf(row.get("author_id"))))
            throw new ApiException(403,"FORBIDDEN","只能修改自己的草稿");
        Map<String,Object> draft = new LinkedHashMap<>();
        draft.put("id",String.valueOf(row.get("id")));
        draft.put("title",row.get("title"));
        draft.put("content",row.get("content"));
        draft.put("version",((Number) row.get("content_version")).intValue());
        return Map.of("draft", draft);
    }

    private Map<String,Object> conversationsContext(Actor actor, List<String> ids) {
        List<Map<String,Object>> conversations = new ArrayList<>();
        for (String raw : ids) {
            long id = Long.parseLong(raw);
            var rows = db.queryForList(
                    "SELECT c.id, CASE WHEN c.user_low=? THEN c.user_high ELSE c.user_low END AS partner_id,"
                    + " u.nickname, m.content AS last_content FROM conversations c"
                    + " JOIN users u ON u.id = CASE WHEN c.user_low=? THEN c.user_high ELSE c.user_low END"
                    + " LEFT JOIN messages m ON m.id = c.last_message_id"
                    + " WHERE c.id=? AND (c.user_low=? OR c.user_high=?)",
                    actor.id(), actor.id(), id, actor.id(), actor.id());
            if (rows.isEmpty()) throw new ApiException(403,"FORBIDDEN","无权访问该会话");
            var row = rows.get(0);
            Map<String,Object> conversation = new LinkedHashMap<>();
            conversation.put("conversation_id",String.valueOf(row.get("id")));
            conversation.put("partner",row.get("nickname"));
            conversation.put("last_message",row.get("last_content"));
            conversations.add(conversation);
        }
        return Map.of("conversations", conversations);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
