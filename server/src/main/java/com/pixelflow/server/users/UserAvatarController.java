package com.pixelflow.server.users;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.MediaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class UserAvatarController {
    public record Input(@NotNull @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        @JsonDeserialize(using=UserController.StrictString.class) String mediaId) {}
    private final JdbcTemplate db;
    private final MediaService media;
    public UserAvatarController(JdbcTemplate db,MediaService media) { this.db=db; this.media=media; }

    @PutMapping("/api/users/me/avatar") @Transactional
    public ResponseEntity<Map<String,String>> set(@AuthenticationPrincipal Actor actor,@Valid @RequestBody Input input) {
        // Same user lock as media deletion/upload and post attachment mutations.
        media.lockOwner(actor);
        if (db.queryForObject("SELECT COUNT(*) FROM media WHERE id=? AND owner_id=?",
                Integer.class,input.mediaId(),actor.id())!=1)
            throw new ApiException(404,"MEDIA_NOT_FOUND","媒体不存在或不属于当前用户");
        String avatar=media.url(input.mediaId());
        db.update("UPDATE users SET avatar_media_id=?,avatar=? WHERE id=?",input.mediaId(),avatar,actor.id());
        return result(actor,avatar);
    }

    @DeleteMapping("/api/users/me/avatar") @Transactional
    public ResponseEntity<Map<String,String>> clear(@AuthenticationPrincipal Actor actor) {
        media.lockOwner(actor);
        db.update("UPDATE users SET avatar_media_id=NULL,avatar='' WHERE id=?",actor.id());
        return result(actor,"");
    }

    private ResponseEntity<Map<String,String>> result(Actor actor,String avatar) {
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .body(Map.of("user_id",actor.id(),"avatar",avatar));
    }
}
