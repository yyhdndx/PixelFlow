package com.pixelflow.server.events;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
public class EventController {
    public record Event(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String eventId,
            @NotBlank String postId,
            @NotNull @Pattern(regexp="EXPOSURE|CLICK|DWELL|LIKE|FAVORITE|FOLLOW|SHARE|NOT_INTERESTED|PUBLISH") String type,
            @NotNull Long occurredAt,
            @Min(1) @Max(1800000) Long durationMs,
            @Pattern(regexp="HOME|DETAIL") String scene) {}
    private final JdbcTemplate db;
    private final PostService posts;
    private final EventWriter writer;
    public EventController(JdbcTemplate db,PostService posts,EventWriter writer) { this.db=db; this.posts=posts; this.writer=writer; }
    @PostMapping("/api/events") @Transactional
    public Map<String,Object> receive(@Valid @RequestBody Event event,@AuthenticationPrincipal Actor actor) {
        long now=Instant.now().getEpochSecond();
        if (event.occurredAt()<now-30*86400 || event.occurredAt()>now+300)
            throw new ApiException(400,"INVALID_ARGUMENT","事件时间须在最近 30 天内且不能超过未来 5 分钟");
        if (!event.postId().matches("[1-9][0-9]{0,17}")) throw new ApiException(400,"INVALID_ARGUMENT","作品 ID 无效");
        if (event.type().equals("DWELL") != (event.durationMs()!=null))
            throw new ApiException(400,"INVALID_ARGUMENT","DWELL 必须提供 durationMs，其他事件不得提供");
        db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",String.class,actor.id());
        var previous=db.queryForList("SELECT * FROM behavior_events WHERE user_id=? AND event_id=?",actor.id(),event.eventId());
        if (!previous.isEmpty()) {
            var row=previous.get(0);
            if (!row.get("post_id").toString().equals(event.postId()) || !row.get("event_type").equals(event.type())
                    || ((Number)row.get("occurred_at")).longValue()!=event.occurredAt()
                    || !Objects.equals(row.get("duration_ms"),event.durationMs())
                    || !Objects.equals(row.get("scene"),event.scene()))
                throw new ApiException(409,"EVENT_CONFLICT","同一 eventId 不能用于不同事件");
            return Map.of("eventId",event.eventId(),"duplicate",true);
        }
        posts.requirePublished(event.postId());
        writer.append(event.eventId(),actor.id(),event.postId(),event.type(),event.occurredAt(),event.durationMs(),event.scene(),"CLIENT");
        return Map.of("eventId",event.eventId(),"duplicate",false);
    }
    @GetMapping("/api/admin/events")
    public List<Map<String,Object>> export(@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int count) {
        PostService.checkPage(count,null);
        if (after<0) throw new ApiException(400,"INVALID_ARGUMENT","after 不得为负数");
        return db.queryForList("SELECT * FROM behavior_events WHERE seq>? ORDER BY seq LIMIT ?",after,count);
    }
}
