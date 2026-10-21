package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.common.NumericPageRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

@RestController
public class ProfileHistoryController {
    private static final List<String> ITEM_FIELDS=List.of("version","rule_version","generated_at","window_days",
            "timezone","cold_start","topics","media","event_counts","explanation");
    private final URI endpoint;
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ProfileHistoryController(ObjectMapper json,@Value("${pixelflow.intelligence-base-url:}") String base) {
        this.json=json;
        if (base.isBlank()) { endpoint=null; return; }
        URI uri=URI.create(base);
        if (!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalStateException("Intelligence URL must be an HTTP(S) origin");
        endpoint=uri.resolve("/profiles/me/history");
    }

    @GetMapping("/api/users/me/profile/history")
    public ObjectNode history(@AuthenticationPrincipal Actor actor,@RequestHeader("Authorization") String token,
            @RequestParam MultiValueMap<String,String> params,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var page=NumericPageRequest.parse(params,"before_version");
        if (endpoint==null) throw unavailable();
        URI uri=URI.create(endpoint+"?count="+page.count()
                +(page.cursor()==null ? "" : "&before_version="+page.cursor()));
        try {
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                    .header("Authorization",token).GET().build();
            var upstream=client.send(request,HttpResponse.BodyHandlers.ofString());
            if (upstream.statusCode()==401) throw new ApiException(401,"UNAUTHORIZED","会话已失效，请重新登录");
            if (upstream.statusCode()!=200) throw unavailable();
            JsonNode body=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(upstream.body());
            return validated(body,actor.id(),page);
        } catch (JsonProcessingException error) {
            throw invalidResponse();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (java.io.IOException error) {
            throw unavailable();
        }
    }

    private ObjectNode validated(JsonNode body,String userId,NumericPageRequest page) {
        if (body==null || !body.isObject() || !body.path("user_id").isTextual()
                || !userId.equals(body.path("user_id").textValue()) || !body.path("items").isArray()
                || body.path("items").size()>page.count() || !body.path("has_more").isBoolean()
                || !body.has("next_cursor")) throw invalidResponse();
        ObjectNode result=json.createObjectNode().put("user_id",userId);
        var items=result.putArray("items");
        Long previous=page.cursor();
        for (JsonNode item:body.get("items")) {
            if (!item.isObject() || !integer(item.path("version")) || item.get("version").longValue()<1)
                throw invalidResponse();
            long version=item.get("version").longValue();
            if (previous!=null && version>=previous) throw invalidResponse();
            previous=version;
            for (String field:List.of("rule_version","timezone","explanation"))
                if (!item.path(field).isTextual()) throw invalidResponse();
            if (!integer(item.path("generated_at")) || !integer(item.path("window_days"))
                    || !item.path("cold_start").isBoolean() || !item.path("event_counts").isObject())
                throw invalidResponse();
            item.get("event_counts").forEach(count -> {
                if (!integer(count) || count.longValue()<0) throw invalidResponse();
            });
            ObjectNode summary=items.addObject();
            for (String field:ITEM_FIELDS) summary.set(field,item.get(field));
            // Only expose the documented summary; historical interests do not use today's suppression list.
            for (String field:List.of("topics","media")) {
                if (!item.path(field).isArray()) throw invalidResponse();
                var weights=summary.putArray(field);
                for (JsonNode weight:item.get(field)) {
                    if (!weight.isObject() || !weight.path("name").isTextual()
                            || !finite(weight.path("score")) || !finite(weight.path("share"))) throw invalidResponse();
                    var projected=weights.addObject();
                    for (String key:List.of("name","score","share")) projected.set(key,weight.get(key));
                }
            }
        }
        boolean more=body.get("has_more").booleanValue();
        JsonNode cursor=body.get("next_cursor");
        if (more) {
            if (items.size()!=page.count() || !cursor.isTextual() || previous==null
                    || !Long.toString(previous).equals(cursor.textValue()) || previous<=1) throw invalidResponse();
        } else if (!cursor.isNull()) throw invalidResponse();
        result.put("has_more",more);
        result.set("next_cursor",cursor);
        return result;
    }

    private static boolean integer(JsonNode node) { return node.isIntegralNumber() && node.canConvertToLong(); }
    private static boolean finite(JsonNode node) { return node.isNumber() && Double.isFinite(node.doubleValue()); }
    private static ApiException invalidResponse() {
        return new ApiException(502,"INVALID_ANALYTICS_RESPONSE","历史画像响应无效");
    }
    private static ApiException unavailable() {
        return new ApiException(503,"ANALYTICS_UNAVAILABLE","历史画像服务暂时不可用");
    }
}
