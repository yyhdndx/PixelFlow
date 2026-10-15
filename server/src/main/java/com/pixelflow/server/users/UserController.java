package com.pixelflow.server.users;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.interaction.PreferenceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestController
public class UserController {
    public record Status(@NotNull Boolean enabled,@NotBlank @Size(max=500) String reason) {}
    public record Topic(@NotNull Boolean suppressed) {}
    private final JdbcTemplate db;
    private final PreferenceService preferences;
    public UserController(JdbcTemplate db,PreferenceService preferences) { this.db=db; this.preferences=preferences; }
    @GetMapping("/api/users/me") public Map<String,Object> me(@AuthenticationPrincipal Actor actor) {
        return db.queryForMap("SELECT id,username,nickname,role,enabled FROM users WHERE id=?",actor.id());
    }
    @PutMapping("/api/admin/users/{id}/status") @Transactional
    public Map<String,Object> status(@PathVariable String id,@Valid @RequestBody Status input,@AuthenticationPrincipal Actor actor) {
        if (actor.id().equals(id)) throw new ApiException(409,"SELF_OPERATION","不能修改自己的账号状态");
        var rows=db.queryForList("SELECT enabled FROM users WHERE id=? FOR UPDATE",id);
        if (rows.isEmpty()) throw new ApiException(404,"USER_NOT_FOUND","用户不存在");
        boolean before=(Boolean)rows.get(0).get("enabled");
        if (before!=input.enabled()) {
            db.update("UPDATE users SET enabled=? WHERE id=?",input.enabled(),id);
            if (!input.enabled()) db.update("DELETE FROM sessions WHERE user_id=?",id);
            db.update("INSERT INTO user_status_audit(actor_id,user_id,before_enabled,after_enabled,reason,created_at) VALUES(?,?,?,?,?,?)",
                    actor.id(),id,before,input.enabled(),input.reason(),Instant.now().getEpochSecond());
        }
        return Map.of("id",id,"enabled",input.enabled());
    }
    @PutMapping("/api/users/me/profile/topics/{topic}")
    public ResponseEntity<Map<String,Object>> topic(@PathVariable String topic,@Valid @RequestBody Topic input,
            @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(preferences.setTopicSuppressed(actor, topic, input.suppressed()));
    }
}
