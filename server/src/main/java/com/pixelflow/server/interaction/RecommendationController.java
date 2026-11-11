package com.pixelflow.server.interaction;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/recommendations/me")
public class RecommendationController {
    public record Personalized(@NotNull Boolean personalized) {}
    public record Blocked(@NotNull Boolean blocked) {}
    public record LegacyImport(@NotBlank @Size(max=36) String source_user_id,
            @NotNull Boolean personalized,
            @NotNull @Size(max=1000) List<@NotNull @Pattern(regexp="[1-9][0-9]{0,17}") String> excluded_post_ids,
            @NotNull @Size(max=500) List<@NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,36}") String> blocked_author_ids) {}
    private final PreferenceService preferences;
    private final com.pixelflow.server.content.PostService posts;
    private final PythonRecommendationClient recommender;
    public RecommendationController(PreferenceService preferences,
                                    com.pixelflow.server.content.PostService posts,
                                    PythonRecommendationClient recommender) {
        this.preferences = preferences; this.posts = posts; this.recommender = recommender;
    }

    /** 首页个性化推荐：Python 排序 + Java 补卡片；任何异常都降级为普通 Feed 首屏。 */
    @GetMapping("/feed")
    public ResponseEntity<Map<String,Object>> feed(@RequestParam(defaultValue = "10") int count,
            @AuthenticationPrincipal Actor actor,
            jakarta.servlet.http.HttpServletRequest request) {
        int size = Math.max(1, Math.min(20, count));
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        var preview = recommender.preview(token);

        java.util.List<Map<String,Object>> cards = new java.util.ArrayList<>();
        String strategy = "fallback";
        String reason = "preview_unavailable";
        Object profileVersion = null;
        if (preview.isPresent()) {
            Map<String,Object> body = preview.get();
            strategy = String.valueOf(body.getOrDefault("strategy", "fallback"));
            reason = String.valueOf(body.getOrDefault("reason", "preview_unavailable"));
            profileVersion = body.get("profile_version");
            Object raw = body.get("items");
            if (raw instanceof java.util.List<?> items) {
                for (Object entry : items) {
                    if (!(entry instanceof Map<?,?> item)) continue;
                    String postId = String.valueOf(item.get("post_id"));
                    Map<String,Object> card = posts.recommendedCard(postId, actor);
                    if (card == null) continue; // Python 结果可能已撤回/不可见，直接跳过并保持顺序
                    Object itemReason = item.get("reason");
                    card.put("recommend_reason", itemReason == null ? "" : String.valueOf(itemReason));
                    card.put("recommend_score", item.get("score"));
                    cards.add(card);
                    if (cards.size() >= size) break;
                }
            }
        }
        if (cards.isEmpty()) {
            Map<String,Object> fallback = posts.feed(size, null, false, actor);
            fallback.put("strategy", "fallback");
            fallback.put("reason", "preview_unavailable");
            fallback.put("fallback", 1);
            return ResponseEntity.ok().header("Cache-Control","no-store").body(fallback);
        }
        long cursor = Long.MAX_VALUE;
        for (Map<String,Object> card : cards) cursor = Math.min(cursor, Long.parseLong(String.valueOf(card.get("post_id"))));
        Map<String,Object> out = new java.util.LinkedHashMap<>();
        out.put("status_code", 0);
        out.put("has_more", 1);
        out.put("post_list", cards);
        out.put("next_cursor", String.valueOf(cursor));
        out.put("strategy", strategy);
        out.put("reason", reason);
        if (profileVersion != null) out.put("profile_version", profileVersion);
        out.put("fallback", 0);
        return ResponseEntity.ok().header("Cache-Control","no-store").body(out);
    }

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

    @PostMapping("/preferences/import")
    public ResponseEntity<Map<String,Object>> importLegacy(@Valid @RequestBody LegacyImport input,
                                                          @AuthenticationPrincipal Actor actor) {
        if (!actor.id().equals(input.source_user_id()))
            throw new ApiException(403,"PREFERENCE_OWNER_MISMATCH","只能导入本人的旧偏好");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(
                preferences.importLegacy(actor, input.personalized(), input.excluded_post_ids(), input.blocked_author_ids()));
    }
}
