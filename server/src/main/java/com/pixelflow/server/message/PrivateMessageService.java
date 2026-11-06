package com.pixelflow.server.message;

import com.pixelflow.server.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;

/** 一对一会话（私信）领域服务：会话去重、历史分页、未读数与已读位置。 */
@Service
public class PrivateMessageService {
    private final JdbcTemplate db;
    public PrivateMessageService(JdbcTemplate db) { this.db = db; }

    private static final String USER_ID = "[A-Za-z0-9_-]{1,36}";

    /** user_low/user_high 按字典序归一化，保证同一对用户只有一个会话。 */
    private static String[] pair(String a, String b) {
        return a.compareTo(b) <= 0 ? new String[]{a, b} : new String[]{b, a};
    }

    private Long queryLong(String sql, Object... args) {
        List<Long> rows = db.query(sql, (rs, i) -> rs.getLong(1), args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------- 创建或返回既有会话 ----------
    public Map<String, Object> getOrCreate(String actorId, String targetUserId) {
        if (targetUserId == null || !targetUserId.matches(USER_ID))
            throw new ApiException(400, "INVALID_ARGUMENT", "目标用户 ID 无效");
        if (actorId.equals(targetUserId))
            throw new ApiException(400, "INVALID_ARGUMENT", "不能和自己发起会话");
        if (db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND enabled=TRUE", Integer.class, targetUserId) == 0)
            throw new ApiException(404, "USER_NOT_FOUND", "用户不存在或已停用");

        String[] p = pair(actorId, targetUserId);
        Long existing = queryLong("SELECT id FROM conversations WHERE user_low=? AND user_high=?", p[0], p[1]);
        if (existing != null) return viewConversation(actorId, existing);

        long now = Instant.now().getEpochSecond();
        try {
            KeyHolder key = new GeneratedKeyHolder();
            db.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO conversations (user_low, user_high, create_time, last_message_id, last_message_time) VALUES (?,?,?,NULL,NULL)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, p[0]); ps.setString(2, p[1]); ps.setLong(3, now);
                return ps;
            }, key);
            return viewConversation(actorId, key.getKey().longValue());
        } catch (DuplicateKeyException duplicate) {
            Long again = queryLong("SELECT id FROM conversations WHERE user_low=? AND user_high=?", p[0], p[1]);
            if (again != null) return viewConversation(actorId, again);
            throw duplicate;
        }
    }

    // ---------- 会话列表 ----------
    public Map<String, Object> list(String actorId, int count, String cursor) {
        long cursorTime, cursorId;
        if (cursor == null) { cursorTime = Long.MAX_VALUE; cursorId = Long.MAX_VALUE; }
        else {
            String[] parts = cursor.split(":", 2);
            try { cursorTime = Long.parseLong(parts[0]); cursorId = Long.parseLong(parts[1]); }
            catch (RuntimeException error) { throw new ApiException(400, "INVALID_ARGUMENT", "cursor 格式无效"); }
        }
        String sql = "SELECT c.id, c.create_time, c.last_message_time, c.last_message_id,"
                + " CASE WHEN c.user_low=? THEN c.user_high ELSE c.user_low END AS partner_id,"
                + " u.nickname, u.avatar, m.content AS last_content, m.create_time AS last_msg_time,"
                + " (SELECT COUNT(*) FROM messages um WHERE um.conversation_id=c.id"
                + "  AND um.id > COALESCE((SELECT last_read_message_id FROM conversation_reads r WHERE r.conversation_id=c.id AND r.user_id=?),0)"
                + "  AND um.sender_id != ?) AS unread_count"
                + " FROM conversations c"
                + " JOIN users u ON u.id = CASE WHEN c.user_low=? THEN c.user_high ELSE c.user_low END"
                + " LEFT JOIN messages m ON m.id = c.last_message_id"
                + " WHERE (c.user_low=? OR c.user_high=?)"
                + " AND (COALESCE(c.last_message_time,c.create_time) < ? OR (COALESCE(c.last_message_time,c.create_time)=? AND c.id < ?))"
                + " ORDER BY COALESCE(c.last_message_time,c.create_time) DESC, c.id DESC LIMIT ?";
        List<Map<String, Object>> rows = db.queryForList(sql, actorId, actorId, actorId, actorId, actorId, actorId,
                cursorTime, cursorTime, cursorId, count + 1);
        boolean more = rows.size() > count;
        List<Map<String, Object>> selected = rows.subList(0, Math.min(count, rows.size()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status_code", 0);
        result.put("conversation_list", selected.stream().map(this::viewRow).toList());
        result.put("has_more", more ? 1 : 0);
        if (more && !selected.isEmpty()) {
            Map<String, Object> last = selected.get(selected.size() - 1);
            long time = last.get("last_message_time") == null
                    ? ((Number) last.get("create_time")).longValue() : ((Number) last.get("last_message_time")).longValue();
            result.put("next_cursor", time + ":" + last.get("conversation_id"));
        } else {
            result.put("next_cursor", null);
        }
        return result;
    }

    // ---------- 历史消息 ----------
    public Map<String, Object> history(String actorId, long conversationId, int count, Long cursor, Long after) {
        requireParticipant(actorId, conversationId);
        List<Map<String, Object>> rows;
        if (after != null) {
            rows = db.queryForList("SELECT * FROM messages WHERE conversation_id=? AND id>? ORDER BY id ASC LIMIT ?",
                    conversationId, after, count + 1);
        } else {
            rows = db.queryForList("SELECT * FROM messages WHERE conversation_id=? AND id<? ORDER BY id DESC LIMIT ?",
                    conversationId, cursor == null ? Long.MAX_VALUE : cursor, count + 1);
        }
        boolean more = rows.size() > count;
        List<Map<String, Object>> selected = rows.subList(0, Math.min(count, rows.size()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status_code", 0);
        result.put("message_list", selected.stream().map(this::messageView).toList());
        result.put("has_more", more ? 1 : 0);
        result.put("next_cursor", more && !selected.isEmpty()
                ? selected.get(selected.size() - 1).get("id").toString() : null);
        return result;
    }

    // ---------- 发送消息 ----------
    public Map<String, Object> send(String actorId, long conversationId, String content) {
        requireParticipant(actorId, conversationId);
        long now = Instant.now().getEpochSecond();
        KeyHolder key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO messages (conversation_id, sender_id, content, create_time) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, conversationId); ps.setString(2, actorId); ps.setString(3, content); ps.setLong(4, now);
            return ps;
        }, key);
        long messageId = key.getKey().longValue();
        db.update("UPDATE conversations SET last_message_id=?, last_message_time=? WHERE id=?", messageId, now, conversationId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message_id", Long.toString(messageId));
        result.put("conversation_id", Long.toString(conversationId));
        result.put("sender_id", actorId);
        result.put("content", content);
        result.put("create_time", now);
        return result;
    }

    // ---------- 标记已读 ----------
    public void markRead(String actorId, long conversationId, Long lastReadMessageId) {
        requireParticipant(actorId, conversationId);
        long target;
        if (lastReadMessageId != null) {
            target = lastReadMessageId;
        } else {
            Long latest = queryLong("SELECT last_message_id FROM conversations WHERE id=?", conversationId);
            target = latest == null ? 0 : latest;
        }
        if (target < 1) return;
        int updated = db.update("UPDATE conversation_reads SET last_read_message_id=? WHERE conversation_id=? AND user_id=? AND last_read_message_id<?",
                target, conversationId, actorId, target);
        if (updated == 0) {
            try {
                db.update("INSERT INTO conversation_reads (conversation_id, user_id, last_read_message_id) VALUES (?,?,?)",
                        conversationId, actorId, target);
            } catch (DuplicateKeyException duplicate) { /* 已存在且位置更靠后，幂等忽略 */ }
        }
    }

    // ---------- 未读总数 ----------
    public long unreadTotal(String actorId) {
        Long total = queryLong("SELECT COUNT(*) FROM messages m"
                + " JOIN conversations c ON m.conversation_id=c.id"
                + " LEFT JOIN conversation_reads r ON r.conversation_id=c.id AND r.user_id=?"
                + " WHERE (c.user_low=? OR c.user_high=?) AND m.sender_id != ?"
                + " AND m.id > COALESCE(r.last_read_message_id, 0)", actorId, actorId, actorId, actorId);
        return total == null ? 0 : total;
    }

    // ---------- 权限与视图 ----------
    private void requireParticipant(String actorId, long conversationId) {
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE id=? AND (user_low=? OR user_high=?)",
                Integer.class, conversationId, actorId, actorId);
        if (count == 0) throw new ApiException(403, "FORBIDDEN", "无权访问该会话");
    }

    private Map<String, Object> viewConversation(String actorId, long conversationId) {
        String sql = "SELECT c.id, c.create_time, c.last_message_time, c.last_message_id,"
                + " CASE WHEN c.user_low=? THEN c.user_high ELSE c.user_low END AS partner_id,"
                + " u.nickname, u.avatar, m.content AS last_content, m.create_time AS last_msg_time,"
                + " (SELECT COUNT(*) FROM messages um WHERE um.conversation_id=c.id"
                + "  AND um.id > COALESCE((SELECT last_read_message_id FROM conversation_reads r WHERE r.conversation_id=c.id AND r.user_id=?),0)"
                + "  AND um.sender_id != ?) AS unread_count"
                + " FROM conversations c"
                + " JOIN users u ON u.id = CASE WHEN c.user_low=? THEN c.user_high ELSE c.user_low END"
                + " LEFT JOIN messages m ON m.id = c.last_message_id"
                + " WHERE c.id=?";
        return viewRow(db.queryForMap(sql, actorId, actorId, actorId, actorId, conversationId));
    }

    private Map<String, Object> viewRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversation_id", row.get("id").toString());
        result.put("create_time", row.get("create_time"));
        result.put("last_message_time", row.get("last_message_time"));
        Map<String, Object> partner = new LinkedHashMap<>();
        partner.put("user_id", row.get("partner_id"));
        partner.put("nickname", row.get("nickname"));
        partner.put("avatar", row.getOrDefault("avatar", ""));
        result.put("partner", partner);
        if (row.get("last_message_id") != null) {
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("message_id", row.get("last_message_id").toString());
            last.put("content", row.get("last_content"));
            last.put("create_time", row.get("last_msg_time"));
            result.put("last_message", last);
        } else {
            result.put("last_message", null);
        }
        result.put("unread_count", ((Number) row.get("unread_count")).intValue());
        return result;
    }

    private Map<String, Object> messageView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message_id", row.get("id").toString());
        result.put("conversation_id", row.get("conversation_id").toString());
        result.put("sender_id", row.get("sender_id"));
        result.put("content", row.get("content"));
        result.put("create_time", row.get("create_time"));
        return result;
    }
}
