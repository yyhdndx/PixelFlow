package com.pixelflow.server.interaction;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class PreferenceService {
    public static final int MAX_EXCLUSIONS = 1000;
    public static final int MAX_BLOCKED_AUTHORS = 500;
    public static final Set<String> SUPPORTED_TOPICS =
            Set.of("美食","宠物","旅行","阅读","数码","运动","园艺");
    private final JdbcTemplate db;
    public PreferenceService(JdbcTemplate db) { this.db = db; }

    public Map<String,Object> read(Actor actor) { return snapshot(actor.id()); }

    public List<String> suppressedTopics(String userId) {
        return db.queryForList("SELECT topic FROM recommendation_suppressed_topics WHERE user_id=? ORDER BY topic",
                String.class, userId);
    }

    @Transactional
    public Map<String,Object> setPersonalized(Actor actor, boolean personalized) {
        lock(actor);
        boolean before = db.queryForObject(
                "SELECT COUNT(*) FROM recommendation_preferences WHERE user_id=? AND personalized=FALSE",
                Integer.class, actor.id()) == 0;
        if (before != personalized) {
            insertRowIfAbsent(actor.id());
            db.update("UPDATE recommendation_preferences SET personalized=?,version=version+1 WHERE user_id=?",
                    personalized, actor.id());
        }
        return snapshot(actor.id());
    }

    @Transactional
    public Map<String,Object> setAuthorBlocked(Actor actor, String authorId, boolean blocked) {
        lock(actor);
        boolean exists = db.queryForObject(
                "SELECT COUNT(*) FROM recommendation_blocked_authors WHERE user_id=? AND author_id=?",
                Integer.class, actor.id(), authorId) > 0;
        if (exists != blocked) {
            if (blocked) {
                if (db.queryForObject("SELECT COUNT(*) FROM recommendation_blocked_authors WHERE user_id=?",
                        Integer.class, actor.id()) >= MAX_BLOCKED_AUTHORS)
                    throw new ApiException(409,"BLOCK_LIMIT","屏蔽作者最多 "+MAX_BLOCKED_AUTHORS+" 个");
                db.update("INSERT INTO recommendation_blocked_authors(user_id,author_id) VALUES(?,?)", actor.id(), authorId);
            } else {
                db.update("DELETE FROM recommendation_blocked_authors WHERE user_id=? AND author_id=?", actor.id(), authorId);
            }
            bump(actor.id());
        }
        return snapshot(actor.id());
    }

    @Transactional
    public Map<String,Object> setTopicSuppressed(Actor actor, String topic, boolean suppressed) {
        lock(actor);
        if (!SUPPORTED_TOPICS.contains(topic)) throw new ApiException(400,"UNSUPPORTED_TOPIC","不支持的主题："+topic);
        boolean exists = db.queryForObject(
                "SELECT COUNT(*) FROM recommendation_suppressed_topics WHERE user_id=? AND topic=?",
                Integer.class, actor.id(), topic) > 0;
        if (exists != suppressed) {
            if (suppressed) db.update("INSERT INTO recommendation_suppressed_topics(user_id,topic) VALUES(?,?)", actor.id(), topic);
            else db.update("DELETE FROM recommendation_suppressed_topics WHERE user_id=? AND topic=?", actor.id(), topic);
            bump(actor.id());
        }
        return snapshot(actor.id());
    }

    /** 供 InteractionController 在自身事务内调用：不感兴趣状态变化后递增版本。 */
    public void bumpVersion(Actor actor) { bump(actor.id()); }

    private void lock(Actor actor) {
        db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", String.class, actor.id());
    }
    private void insertRowIfAbsent(String userId) {
        if (db.queryForObject("SELECT COUNT(*) FROM recommendation_preferences WHERE user_id=?", Integer.class, userId) == 0)
            db.update("INSERT INTO recommendation_preferences(user_id) VALUES(?)", userId);
    }
    private void bump(String userId) {
        insertRowIfAbsent(userId);
        db.update("UPDATE recommendation_preferences SET version=version+1 WHERE user_id=?", userId);
    }
    private Map<String,Object> snapshot(String userId) {
        boolean personalized = db.queryForObject(
                "SELECT COUNT(*) FROM recommendation_preferences WHERE user_id=? AND personalized=FALSE",
                Integer.class, userId) == 0;
        long version = 0L;
        var rows = db.queryForList("SELECT version FROM recommendation_preferences WHERE user_id=?", userId);
        if (!rows.isEmpty()) version = ((Number) rows.get(0).get("version")).longValue();
        return Map.of("user_id", userId, "personalized", personalized, "version", version,
                "excluded_post_ids", db.queryForList(
                        "SELECT target_id FROM interactions WHERE user_id=? AND kind='not-interested' ORDER BY length(target_id),target_id",
                        String.class, userId),
                "blocked_author_ids", db.queryForList(
                        "SELECT author_id FROM recommendation_blocked_authors WHERE user_id=? ORDER BY author_id",
                        String.class, userId),
                "suppressed_topics", db.queryForList(
                        "SELECT topic FROM recommendation_suppressed_topics WHERE user_id=? ORDER BY topic",
                        String.class, userId));
    }
}
