package com.pixelflow.server.copilot;

import com.pixelflow.server.common.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 智能体内部只读接口：仅供 intelligence 服务以服务令牌调用，且只能读取指定用户自己的私信。 */
@RestController
public class AgentInternalController {
    private static final String UUID_RE = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final JdbcTemplate db;

    public AgentInternalController(JdbcTemplate db) { this.db = db; }

    /** 我最近的会话：对方昵称、最后一条消息、未读数。 */
    @GetMapping("/internal/agent/conversations")
    public Map<String,Object> conversations(@RequestHeader("X-Actor-Id") String rawActor,
                                            @RequestParam(defaultValue = "8") int limit) {
        String actor = actor(rawActor);
        int size = Math.max(1, Math.min(20, limit));
        var rows = db.query("""
                SELECT c.id, c.user_low, c.user_high, c.last_message_time,
                       u.nickname AS peer_name,
                       m.content AS last_message,
                       (SELECT COUNT(*) FROM messages x
                          LEFT JOIN conversation_reads r ON r.conversation_id=x.conversation_id AND r.user_id=?
                         WHERE x.conversation_id=c.id AND x.sender_id<>?
                           AND x.id > COALESCE(r.last_read_message_id,0)) AS unread
                  FROM conversations c
                  JOIN users u ON u.id = CASE WHEN c.user_low=? THEN c.user_high ELSE c.user_low END
                  LEFT JOIN messages m ON m.id = c.last_message_id
                 WHERE c.user_low=? OR c.user_high=?
                 ORDER BY c.last_message_time DESC
                 LIMIT ?""", (rs, i) -> {
            String peer = actor.equals(rs.getString("user_low"))
                    ? rs.getString("user_high") : rs.getString("user_low");
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("conversation_id", rs.getLong("id"));
            row.put("peer_id", peer);
            row.put("peer_name", rs.getString("peer_name"));
            row.put("last_message", trim(rs.getString("last_message"), 120));
            row.put("last_time", rs.getLong("last_message_time"));
            row.put("unread", rs.getInt("unread"));
            return row;
        }, actor, actor, actor, actor, actor, size);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("conversations", rows);
        return out;
    }

    /** 与某个人的最近消息；before_id 可继续向前翻页。 */
    @GetMapping("/internal/agent/conversations/{peerId}/messages")
    public Map<String,Object> messages(@RequestHeader("X-Actor-Id") String rawActor,
                                       @PathVariable String peerId,
                                       @RequestParam(defaultValue = "0") long beforeId,
                                       @RequestParam(defaultValue = "20") int limit) {
        String actor = actor(rawActor);
        String peer = actor(peerId);
        if (actor.equals(peer)) throw new ApiException(400,"INVALID_ARGUMENT","peerId 不能是自己");
        String low = actor.compareTo(peer) < 0 ? actor : peer;
        String high = actor.compareTo(peer) < 0 ? peer : actor;
        var chats = db.queryForList("SELECT id FROM conversations WHERE user_low=? AND user_high=?", low, high);
        if (chats.isEmpty()) throw new ApiException(404,"NOT_FOUND","没有与该用户的会话");
        long conversationId = ((Number) chats.get(0).get("id")).longValue();
        int size = Math.max(1, Math.min(50, limit));
        var rows = db.query("""
                SELECT id, sender_id, content, create_time FROM messages
                 WHERE conversation_id=? AND (? = 0 OR id < ?)
                 ORDER BY id DESC LIMIT ?""", (rs, i) -> {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("from_me", actor.equals(rs.getString("sender_id")));
            row.put("text", trim(rs.getString("content"), 500));
            row.put("time", rs.getLong("create_time"));
            return row;
        }, conversationId, beforeId, beforeId, size);
        List<Map<String,Object>> ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);
        String peerName = db.queryForList("SELECT nickname FROM users WHERE id=?", String.class, peer)
                .stream().findFirst().orElse("");
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("peer_id", peer);
        out.put("peer_name", peerName);
        out.put("messages", ordered);
        out.put("has_more", rows.size() == size);
        if (!rows.isEmpty()) out.put("next_before_id", ((Number) rows.get(rows.size()-1).get("id")).longValue());
        return out;
    }

    private static String actor(String value) {
        if (value == null || !value.matches(UUID_RE)) throw new ApiException(400,"INVALID_ARGUMENT","actor 非法");
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String trim(String text, int max) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }
}
