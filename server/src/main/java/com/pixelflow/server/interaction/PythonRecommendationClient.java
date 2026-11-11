package com.pixelflow.server.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 转发用户 token 调用 Python 推荐预览；失败返回 empty，由首页降级为普通 Feed。 */
@Component
public class PythonRecommendationClient {
    private static final Duration BUDGET = Duration.ofSeconds(20);
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final URI origin;

    public PythonRecommendationClient(ObjectMapper json,
            @Value("${pixelflow.intelligence-base-url:}") String base) {
        this.json = json;
        if (base == null || base.isBlank()) { origin = null; return; }
        URI uri = URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalStateException("Intelligence URL must be an HTTP(S) origin");
        origin = uri;
    }

    public Optional<Map<String,Object>> preview(String userToken) {
        if (origin == null || userToken == null || userToken.isBlank()) return Optional.empty();
        try {
            var request = HttpRequest.newBuilder(origin.resolve("/recommendations/me/preview"))
                    .timeout(BUDGET)
                    .header("Authorization", "Bearer " + userToken)
                    .header("Accept", "application/json")
                    .GET().build();
            var reply = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (reply.statusCode() != 200) return Optional.empty();
            return Optional.of(json.readValue(reply.body(), Map.class));
        } catch (Exception error) {
            return Optional.empty();
        }
    }
}