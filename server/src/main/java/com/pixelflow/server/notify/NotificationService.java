package com.pixelflow.server.notify;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** 生成用户通知：@提及 / 评论回复 / 作品点赞。屏蔽规则与「不通知自己」在此统一处理。 */
@Service
public class NotificationService {
    public static final Set<String> TYPES = Set.of("MENTION", "REPLY", "LIKE");
    private final JdbcTemplate db;

    public NotificationService(JdbcTemplate db) { this.db = db; }

    public Set<String> mutedTypes(String userId) {
        String raw = db.queryForObject("SELECT muted_notify_types FROM users WHERE id=?", String.class, userId);
        Set<String> muted = new HashSet<>();
        if (raw != null) for (String part : raw.split(",")) {
            part = part.trim();
            if (TYPES.contains(part)) muted.add(part);
        }
        return muted;
    }

    public boolean isMuted(String userId, String type) { return mutedTypes(userId).contains(type); }

    /** 写入一条通知；自己触发自己、或接收方屏蔽了该类型时忽略。 */
    public void notify(String recipientId, String type, String actorId, long postId, Long commentId) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("未知通知类型: " + type);
        if (recipientId.equals(actorId)) return;
        if (isMuted(recipientId, type)) return;
        db.update("INSERT INTO notifications(recipient_id,type,actor_id,post_id,comment_id,read_flag,create_time) "
                + "VALUES(?,?,?,?,?,FALSE,?)", recipientId, type, actorId, postId, commentId,
                java.time.Instant.now().getEpochSecond());
    }

    public void saveMutedTypes(String userId, Set<String> muted) {
        List<String> sorted = new ArrayList<>(muted);
        Collections.sort(sorted);
        db.update("UPDATE users SET muted_notify_types=? WHERE id=?", String.join(",", sorted), userId);
    }
}
