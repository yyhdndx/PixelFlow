package com.pixelflow.server.interaction;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/recommendations/me")
public class RecommendationController {
    public record Personalized(@NotNull Boolean personalized) {}
    public record Blocked(@NotNull Boolean blocked) {}
    private final PreferenceService preferences;
    public RecommendationController(PreferenceService preferences) { this.preferences = preferences; }

    @GetMapping("/preferences")
    public ResponseEntity<Map<String,Object>> get(@AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(preferences.read(actor));
    }

    @PutMapping("/preferences")
    public ResponseEntity<Map<String,Object>> set(@Valid @RequestBody Personalized input, @AuthenticationPrincipal Actor actor) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(preferences.setPersonalized(actor, input.personalized()));
    }

    @PutMapping("/blocked-authors/{author_id}")
    public ResponseEntity<Map<String,Object>> block(@PathVariable("author_id") String authorId,
            @Valid @RequestBody Blocked input, @AuthenticationPrincipal Actor actor) {
        if (!authorId.matches("[A-Za-z0-9_-]{1,36}"))
            throw new ApiException(400,"INVALID_ARGUMENT","作者 ID 格式无效");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(preferences.setAuthorBlocked(actor, authorId, input.blocked()));
    }
}
