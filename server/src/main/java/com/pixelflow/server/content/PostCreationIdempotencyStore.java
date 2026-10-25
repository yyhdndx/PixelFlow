package com.pixelflow.server.content;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class PostCreationIdempotencyStore {
    public record Entry(String fingerprint,long postId) {}
    private final JdbcTemplate db;
    public PostCreationIdempotencyStore(JdbcTemplate db) { this.db=db; }
    public Optional<Entry> lock(String userId,String key) {
        return db.query("SELECT request_fingerprint,post_id FROM post_creation_idempotency WHERE user_id=? AND request_key=? FOR UPDATE",
                (rs,n) -> new Entry(rs.getString(1),rs.getLong(2)),userId,key).stream().findFirst();
    }
    public void save(String userId,String key,String fingerprint,long postId) {
        db.update("INSERT INTO post_creation_idempotency(user_id,request_key,request_fingerprint,post_id) VALUES(?,?,?,?)",
                userId,key,fingerprint,postId);
    }
}
