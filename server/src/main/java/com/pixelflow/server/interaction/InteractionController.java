package com.pixelflow.server.interaction;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/interactions")
public class InteractionController {
    public record Input(@NotBlank @Size(max=36) String targetId,@NotNull Boolean active,
            @Size(max=36) String recommendationId,
            @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String eventId) {}
    private final JdbcTemplate db;
    private final PostService posts;
    private final com.pixelflow.server.events.EventWriter events;
    private final PreferenceService preferences;
    public InteractionController(JdbcTemplate db,PostService posts,com.pixelflow.server.events.EventWriter events,PreferenceService preferences) { this.db=db; this.posts=posts; this.events=events; this.preferences=preferences; }
    private void kind(String kind) {
        if (!Set.of("likes","favorites","follows","not-interested").contains(kind)) throw new ApiException(404,"NOT_FOUND","互动类型不存在");
    }
    @PutMapping("/{kind}") @Transactional
    public Map<String,Object> set(@PathVariable String kind,@Valid @RequestBody Input input,@AuthenticationPrincipal Actor actor) {
        kind(kind);
        // 同一用户的写操作串行，配合复合主键避免网络重试和并发重复计数。
        db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",String.class,actor.id());
        boolean before=db.queryForObject("SELECT COUNT(*) FROM interactions WHERE user_id=? AND kind=? AND target_id=?",
                Integer.class,actor.id(),kind,input.targetId())>0;
        if (input.active()) {
            if (kind.equals("follows")) {
                if (actor.id().equals(input.targetId())) throw new ApiException(400,"INVALID_ARGUMENT","不能关注自己");
                if (db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND enabled=TRUE",Integer.class,input.targetId())==0)
                    throw new ApiException(404,"USER_NOT_FOUND","用户不存在");
            } else posts.requirePublished(input.targetId());
            if (kind.equals("not-interested") && input.active() && !before
                    && db.queryForObject("SELECT COUNT(*) FROM interactions WHERE user_id=? AND kind='not-interested'",
                            Integer.class,actor.id()) >= PreferenceService.MAX_EXCLUSIONS)
                throw new ApiException(409,"EXCLUSION_LIMIT","不感兴趣的作品最多 "+PreferenceService.MAX_EXCLUSIONS+" 个");
            if (db.queryForObject("SELECT COUNT(*) FROM interactions WHERE user_id=? AND kind=? AND target_id=?",Integer.class,actor.id(),kind,input.targetId())==0)
                db.update("INSERT INTO interactions(user_id,kind,target_id) VALUES(?,?,?)",actor.id(),kind,input.targetId());
        } else db.update("DELETE FROM interactions WHERE user_id=? AND kind=? AND target_id=?",actor.id(),kind,input.targetId());
        if (kind.equals("likes") && before!=input.active())
            events.append(UUID.randomUUID().toString(),actor.id(),input.targetId(),input.active() ? "LIKE" : "UNLIKE",
                    java.time.Instant.now().getEpochSecond(),null,null,"SERVER");
        if (kind.equals("not-interested") && before!=input.active()) {
            preferences.bumpVersion(actor);
            if (input.active())
                events.append(input.eventId()==null ? UUID.randomUUID().toString() : input.eventId(),
                        actor.id(), input.targetId(), "NOT_INTERESTED",
                        java.time.Instant.now().getEpochSecond(), null, null, "SERVER", input.recommendationId());
        }
        return Map.of("targetId",input.targetId(),"active",input.active());
    }
    @GetMapping("/{kind}") public List<Map<String,Object>> list(@PathVariable String kind,@AuthenticationPrincipal Actor actor,
            @RequestParam(defaultValue="50") int count,@RequestParam(defaultValue="") String after) {
        kind(kind); PostService.checkPage(count,null);
        if (after.length()>36) throw new ApiException(400,"INVALID_ARGUMENT","游标过长");
        return db.queryForList("SELECT target_id FROM interactions WHERE user_id=? AND kind=? AND target_id>? ORDER BY target_id LIMIT ?",actor.id(),kind,after,count);
    }
}
