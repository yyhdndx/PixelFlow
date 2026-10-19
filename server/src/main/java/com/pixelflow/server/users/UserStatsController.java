package com.pixelflow.server.users;

import com.pixelflow.server.auth.AuthService.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class UserStatsController {
    private final JdbcTemplate db;
    public UserStatsController(JdbcTemplate db) { this.db=db; }

    @GetMapping("/api/users/me/stats")
    public ResponseEntity<Map<String,Object>> stats(@AuthenticationPrincipal Actor actor) {
        Map<String,Object> row=db.queryForMap(
                "SELECT (SELECT COUNT(*) FROM posts WHERE author_id=? AND status='PUBLISHED') AS post_count,"
                +" (SELECT COUNT(*) FROM interactions f JOIN users u ON f.user_id=u.id"
                +"   WHERE f.kind='follows' AND f.target_id=? AND u.enabled=TRUE) AS follower_count,"
                +" (SELECT COUNT(*) FROM interactions f JOIN users u ON f.target_id=u.id"
                +"   WHERE f.kind='follows' AND f.user_id=? AND u.enabled=TRUE) AS following_count",
                actor.id(),actor.id(),actor.id());
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of(
                "user_id",actor.id(),
                "post_count",((Number)row.get("post_count")).longValue(),
                "follower_count",((Number)row.get("follower_count")).longValue(),
                "following_count",((Number)row.get("following_count")).longValue()));
    }
}
