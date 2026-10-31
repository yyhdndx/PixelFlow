package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class CreationAssistController {
    public record AssistDraft(@NotNull @Size(max=120) String title, @NotNull @Size(max=5000) String content,
            @Size(max=20) List<@Size(max=32) String> tags) {
        public AssistDraft { tags = tags == null ? List.of() : tags; }
    }
    public record CreateAssistRequest(@NotNull @Valid AssistDraft draft,
            @NotBlank @Pattern(regexp="improve_title|suggest_tags|polish_content|write_content|all") String goal,
            @Size(max=200) String prompt, @Size(max=64) String audience) {}
    public record AssistApplication(@NotNull @Size(max=120) String title, @NotNull @Size(max=5000) String content,
            @Min(0) long expected_version) {}

    private final URI endpoint;
    private final ObjectMapper json;
    private final PostService posts;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public CreationAssistController(ObjectMapper json, PostService posts,
            @Value("${pixelflow.intelligence-base-url:}") String base) {
        this.json = json;
        this.posts = posts;
        if (base.isBlank()) { endpoint = null; return; }
        URI uri = URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalStateException("Intelligence URL must be an HTTP(S) origin");
        endpoint = uri.resolve("/agent/create-assist");
    }

    @PostMapping("/api/agent/create-assist")
    public ResponseEntity<JsonNode> createAssist(@Valid @RequestBody CreateAssistRequest body,
            @AuthenticationPrincipal Actor actor, @RequestHeader("Authorization") String token) {
        if (endpoint == null) throw unavailable();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draft", Map.of("title", body.draft().title(), "content", body.draft().content(),
                "tags", body.draft().tags()));
        payload.put("goal", body.goal());
        payload.put("prompt", body.prompt() == null ? "" : body.prompt());
        payload.put("audience", body.audience() == null ? "" : body.audience());
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("Authorization", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var upstream = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (upstream.statusCode() == 401) throw new ApiException(401, "UNAUTHORIZED", "会话已失效，请重新登录");
            if (upstream.statusCode() != 200) throw unavailable();
            JsonNode result = json.readTree(upstream.body());
            if (!result.isObject() || !result.path("user_id").isTextual()
                    || !actor.id().equals(result.path("user_id").textValue())
                    || !result.path("suggestion_id").isTextual() || !result.path("safety").isObject())
                throw invalidResponse();
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        } catch (java.io.IOException error) {
            throw unavailable();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    @PostMapping("/api/posts/{id}/assist-application")
    public Map<String, Object> applyAssist(@PathVariable long id, @AuthenticationPrincipal Actor actor,
            @Valid @RequestBody AssistApplication body) {
        return posts.applyAssist(id, actor, body.title(), body.content(), body.expected_version());
    }

    private static ApiException unavailable() { return new ApiException(503, "ASSIST_UNAVAILABLE", "创作辅助服务暂时不可用"); }
    private static ApiException invalidResponse() { return new ApiException(502, "INVALID_ASSIST_RESPONSE", "创作辅助响应无效"); }
}
