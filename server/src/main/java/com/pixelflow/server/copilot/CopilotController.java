package com.pixelflow.server.copilot;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
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
    public record Request(@NotBlank @Pattern(regexp="detail|create|messages|general") String screen,
                          @Pattern(regexp="[1-9][0-9]{0,18}") String postId,
                          @Pattern(regexp="[1-9][0-9]{0,18}") String draftId,
                          @Size(max=MAX_CONVERSATIONS) List<@Pattern(regexp="[1-9][0-9]{0,18}") String> conversationIds,
                          @Size(max=500) String selection,
                          @NotBlank @Size(max=2000) String message,
                          @Size(max=400) String summary,
                          @Size(max=MAX_HISTORY) List<@Valid HistoryItem> history) {}

    @PostMapping("/api/agent/copilot/summary")
    public Map<String,Object> summary(@AuthenticationPrincipal Actor actor,
                                      @Valid @RequestBody SummaryRequest body) {
        var history = new ArrayList<Map<String,Object>>();
        for (var item : body.messages() == null ? List.<HistoryItem>of() : body.messages())
            history.add(Map.of("role", item.role(), "content", item.content()));
        String text = advisor.compress(body.previousSummary() == null ? "" : body.previousSummary(), history)
                .orElseThrow(() -> new ApiException(503,"AI_UNAVAILABLE","摘要生成失败，请稍后再试"));
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("status_code", 0);
        out.put("summary", text);
        return out;
    }

    public record SummaryRequest(@Size(max=400) String previousSummary,
                                 @Size(max=MAX_HISTORY) List<@Valid HistoryItem> messages) {}

    public record Application(@Size(max=120) String title,
                              @NotBlank @Size(max=5000) String content,
                              @NotNull @Min(0) Integer expected_version) {}

    private final JdbcTemplate db;
    private final CopilotAdvisor advisor;
    private final PostService posts;

    public CopilotController(JdbcTemplate db, CopilotAdvisor advisor, PostService posts) {
        this.db = db; this.advisor = advisor; this.posts = posts;
    }

    /** 应用 Copilot 的草稿修改：带 content_version 的乐观锁，冲突返回 409 VERSION_CONFLICT。 */
    @PostMapping("/api/posts/{id}/copilot-application")
    public Map<String,Object> apply(@PathVariable long id,@AuthenticationPrincipal Actor actor,
                                    @Valid @RequestBody Application body) {
        return posts.applyAssist(id, actor, body.title() == null ? "" : body.title(),
                body.content(), body.expected_version());
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
        } else if ("general".equals(screen)) {
            context = generalContext(actor, input.message());
        } else {
            var ids = input.conversationIds() == null ? List.<String>of() : input.conversationIds();
            if (ids.isEmpty()) throw new ApiException(400,"INVALID_ARGUMENT","messages 需要 conversationIds");
            allowedConversations = new LinkedHashSet<>(ids);
            context = conversationsContext(actor, ids);
        }

        var history = new ArrayList<Map<String,Object>>();
        for (var item : input.history() == null ? List.<HistoryItem>of() : input.history())
            history.add(Map.of("role", item.role(), "content", item.content()));

        if (input.summary() != null && !input.summary().isBlank()) context.put("summary", input.summary());
        var result = advisor.chat(screen, context, input.message(), history);
        if (result.isEmpty())
            throw new ApiException(503,"AI_UNAVAILABLE","AI 暂时不可用，请稍后再试");

        List<Map<String,Object>> actions = validate(screen, result.get().actions(), context, allowedConversations);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("status_code", 0);
        response.put("reply", result.get().reply());
        response.put("actions", actions);
        if (!result.get().references().isEmpty()) response.put("references", result.get().references());
        response.put("screen", screen);
        response.put("trace_id", result.get().traceId());
        return response;
    }

    /** 通用对话：检索交给模型通过 search_site 工具决定，这里只给空参考。 */
    private Map<String,Object> generalContext(com.pixelflow.server.auth.AuthService.Actor actor, String message) {
        Map<String,Object> context = new LinkedHashMap<>();
        context.put("references", List.of());
        // 只把调用者自己的 id 交给模型侧工具，用于读取其私信；越权在 /internal/agent 再校验一次
        if (actor != null && actor.id() != null) context.put("actorId", actor.id());
        return context;
    }
    /** 二次校验：即使模型或 Python 端出错，也不让越界动作离开网关。 */
    /** 允许模型给会话 id 或对方 user id：都解析成属于当前用户的会话。 */
    private Long resolveConversation(Map<String,Object> context, String raw) {
        String actorId = String.valueOf(context.getOrDefault("actorId", ""));
        if (actorId.isEmpty()) return null;
        if (raw != null && raw.matches("[1-9][0-9]{0,18}")) {
            Long count = db.queryForObject(
                    "SELECT COUNT(*) FROM conversations WHERE id=? AND (user_low=? OR user_high=?)",
                    Long.class, Long.parseLong(raw), actorId, actorId);
            return count != null && count > 0 ? Long.parseLong(raw) : null;
        }
        if (raw != null && raw.matches("[0-9a-fA-F-]{36}")) {
            String peer = raw.toLowerCase(java.util.Locale.ROOT);
            if (peer.equals(actorId)) return null;
            String low = actorId.compareTo(peer) < 0 ? actorId : peer;
            String high = actorId.compareTo(peer) < 0 ? peer : actorId;
            var rows = db.queryForList("SELECT id FROM conversations WHERE user_low=? AND user_high=?", low, high);
            return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
        }
        return null;
    }
    /** 校验会话确实属于当前用户（通用对话代回私信时使用）。 */
    private boolean ownsConversation(Map<String,Object> context, String conversationId) {
        Object actorId = context.get("actorId");
        if (actorId == null) return false;
        Long count = db.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE id=? AND (user_low=? OR user_high=?)",
                Long.class, Long.parseLong(conversationId), String.valueOf(actorId), String.valueOf(actorId));
        return count != null && count > 0;
    }

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
            } else if ("general".equals(screen) && "CREATE_DRAFT".equals(type)) {
                String content = string(action.get("content"));
                String title = string(action.get("title"));
                if (content.isEmpty() && title.isEmpty()) continue;
                if (content.length() > 5000 || title.length() > 120) continue;
                Map<String,Object> item = new LinkedHashMap<>();
                item.put("type","CREATE_DRAFT"); item.put("content",content);
                if (!title.isEmpty()) item.put("title",title);
                item.put("rationale",rationale);
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
            } else if ("general".equals(screen) && "SEND_MESSAGES".equals(type)) {
                // 通用对话里代回私信：会话必须属于调用者本人
                List<Map<String,Object>> items = new ArrayList<>();
                Set<String> seenConversations = new HashSet<>();
                Object rawGeneral = action.get("items");
                if (rawGeneral instanceof List<?> list) {
                    for (Object entry : list) {
                        if (!(entry instanceof Map<?,?> map)) continue;
                        String conversation = string(map.get("conversation_id"));
                        String text = string(map.get("text"));
                        if (!conversation.matches("[1-9][0-9]{0,18}") || !seenConversations.add(conversation)) continue;
                        if (text.isEmpty() || text.length() > MAX_MESSAGE) continue;
                        if (!ownsConversation(context, conversation)) continue;
                        Map<String,Object> message = new LinkedHashMap<>();
                        message.put("conversation_id", conversation);
                        message.put("text", text);
                        items.add(message);
                        if (items.size() >= MAX_CONVERSATIONS) break;
                    }
                }
                if (!items.isEmpty()) {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("type","SEND_MESSAGES"); item.put("items",items);
                    item.put("rationale",rationale);
                    kept.add(item);
                }
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
