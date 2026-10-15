package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.interaction.PreferenceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
public class ProfileController {
    private final URI endpoint;
    private final ObjectMapper json;
    private final PreferenceService preferences;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public ProfileController(ObjectMapper json,@Value("${pixelflow.intelligence-base-url:}") String base,PreferenceService preferences) {
        this.json=json;
        this.preferences=preferences;
        if (base.isBlank()) { endpoint=null; return; }
        URI uri=URI.create(base);
        if (!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalStateException("Intelligence URL must be an HTTP(S) origin");
        endpoint=uri.resolve("/profiles/me");
    }
    @GetMapping("/api/users/me/profile")
    public ResponseEntity<?> profile(@AuthenticationPrincipal Actor actor,@RequestHeader("Authorization") String token) {
        if (endpoint==null) throw new ApiException(503,"ANALYTICS_UNAVAILABLE","画像服务尚未配置");
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Authorization",token).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()==401) throw new ApiException(401,"UNAUTHORIZED","会话已失效，请重新登录");
            if (response.statusCode()!=200) throw new ApiException(503,"ANALYTICS_UNAVAILABLE","画像服务暂时不可用");
            var body=json.readTree(response.body());
            if (body==null || !body.isObject() || !actor.id().equals(body.path("user_id").asText())
                    || !body.path("version").isIntegralNumber() || !body.path("topics").isArray())
                throw new ApiException(502,"INVALID_ANALYTICS_RESPONSE","画像响应无效");
            ObjectNode result = (ObjectNode) body;
            ArrayNode topics = (ArrayNode) result.get("topics");
            ArrayNode filtered = json.createArrayNode();
            List<String> suppressed = preferences.suppressedTopics(actor.id());
            for (JsonNode topic : topics)
                if (!suppressed.contains(topic.path("name").asText())) filtered.add(topic);
            result.set("topics", filtered);
            ArrayNode suppressedArr = json.createArrayNode();
            for (String s : suppressed) suppressedArr.add(s);
            result.set("suppressed_topics", suppressedArr);
            return ResponseEntity.ok().header("Cache-Control","no-store").body(result);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(503,"ANALYTICS_UNAVAILABLE","画像请求已中断");
        } catch (java.io.IOException error) {
            throw new ApiException(503,"ANALYTICS_UNAVAILABLE","画像服务暂时不可用");
        }
    }
}
