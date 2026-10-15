package com.pixelflow.server.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Instant;

@Service
public class EventWriter {
    private final JdbcTemplate db;
    public EventWriter(JdbcTemplate db) { this.db=db; }
    // Caller owns a business transaction. A global row lock orders allocation and commit,
    // avoiding gaps from concurrently committed identity values during incremental export.
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(String eventId,String userId,String postId,String type,long occurredAt,
            Long duration,String scene,String source) {
        append(eventId,userId,postId,type,occurredAt,duration,scene,source,null);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(String eventId,String userId,String postId,String type,long occurredAt,
            Long duration,String scene,String source,String recommendationId) {
        long seq=db.queryForObject("SELECT last_seq FROM event_export_state WHERE id=1 FOR UPDATE",Long.class)+1;
        db.update("UPDATE event_export_state SET last_seq=? WHERE id=1",seq);
        db.update("INSERT INTO behavior_events(seq,event_id,user_id,post_id,event_type,occurred_at,received_at,duration_ms,scene,source,recommendation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                seq,eventId,userId,Long.parseLong(postId),type,occurredAt,Instant.now().getEpochSecond(),duration,scene,source,recommendationId);
    }
}
