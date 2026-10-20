package com.pixelflow.server.users;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Public fields only; access still requires a valid session. */
@RestController
public class PublicUserProfileController {
    public record Profile(String user_id,String nickname,String bio,String avatar,
                          long post_count,long follower_count,long following_count,boolean is_following) {}
    private final JdbcTemplate db;
    public PublicUserProfileController(JdbcTemplate db) { this.db=db; }

    @GetMapping("/api/users/{id}/profile")
    public ResponseEntity<Profile> profile(@PathVariable String id,@AuthenticationPrincipal Actor actor) {
        if (!id.matches("[A-Za-z0-9_-]{1,36}"))
            throw new ApiException(400,"INVALID_ARGUMENT","用户 ID 格式不正确");
        var rows=db.query("SELECT u.id,u.nickname,u.bio,u.avatar,"
                +" (SELECT COUNT(*) FROM posts p WHERE p.author_id=u.id AND p.status='PUBLISHED') AS post_count,"
                +" (SELECT COUNT(*) FROM interactions f JOIN users a ON a.id=f.user_id"
                +" WHERE f.kind='follows' AND f.target_id=u.id AND a.enabled=TRUE) AS follower_count,"
                +" (SELECT COUNT(*) FROM interactions f JOIN users a ON a.id=f.target_id"
                +" WHERE f.kind='follows' AND f.user_id=u.id AND a.enabled=TRUE) AS following_count,"
                +" (SELECT COUNT(*) FROM interactions f WHERE f.kind='follows' AND f.user_id=? AND f.target_id=u.id) AS followed"
                +" FROM users u WHERE u.id=? AND u.enabled=TRUE",
                (row,index) -> new Profile(row.getString("id"),row.getString("nickname"),row.getString("bio"),
                        row.getString("avatar"),row.getLong("post_count"),row.getLong("follower_count"),
                        row.getLong("following_count"),row.getLong("followed")>0),actor.id(),id);
        if (rows.isEmpty()) throw new ApiException(404,"USER_NOT_FOUND","用户不存在或不可见");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(rows.get(0));
    }
}
