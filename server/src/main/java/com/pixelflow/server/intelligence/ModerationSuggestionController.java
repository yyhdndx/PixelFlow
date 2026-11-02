package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pixelflow.server.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** ADMIN authorization is enforced by the existing admin-path security rule. */
@RestController
public class ModerationSuggestionController {
    static final Duration BUDGET=Duration.ofSeconds(15);
    static final Set<String> RISKS=Set.of("low","medium","high");
    static final Set<String> ACTIONS=Set.of("approve","review","reject");
    public record SuggestionRequest(@NotBlank @Pattern(regexp="^[1-9][0-9]{0,17}$") String post_id) {}
    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final URI origin;
    private final String serviceToken;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ModerationSuggestionController(ObjectMapper json, JdbcTemplate db,
            @Value("${pixelflow.intelligence-base-url:}") String base,
            @Value("${PIXELFLOW_INTELLIGENCE_TOKEN:}") String token) {
        this.json=json; this.db=db; serviceToken=token;
        if (base.isBlank()) { origin=null; return; }
        URI uri=URI.create(base);
        if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalStateException("Intelligence URL must be an HTTP(S) origin");
        origin=uri;
    }

    @PostMapping("/api/admin/agent/moderation-suggestion")
    public ResponseEntity<JsonNode> suggest(@Valid @RequestBody SuggestionRequest body) {
        return suggest(body,BUDGET);
    }

    // Package-visible budget allows deterministic deadline tests without a long sleep.
    ResponseEntity<JsonNode> suggest(SuggestionRequest body, Duration budget) {
        var posts=db.queryForList("SELECT title,content,status FROM posts WHERE id=?",Long.parseLong(body.post_id()));
        if (posts.isEmpty()) throw new ApiException(404,"NOT_FOUND","作品不存在");
        var post=posts.get(0);
        var root=fetch(body.post_id(),String.valueOf(post.get("title")),String.valueOf(post.get("content")),budget);
        var suggestion=root.path("suggestion");
        var result=project(suggestion,body.post_id());
        // Never echo our shared credential even if an upstream field accidentally contains it.
        if (!serviceToken.isEmpty() && result.toString().contains(serviceToken)) throw invalid();
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .body(json.createObjectNode().put("status_code",0).set("suggestion",result));
    }

    JsonNode fetch(String postId,String title,String content,Duration budget) {
        if (origin==null || serviceToken.length()<32 || !serviceToken.chars().allMatch(c->c>=33 && c<=126))
            throw unavailable();
        long deadline=System.nanoTime()+budget.toNanos();
        CompletableFuture<HttpResponse<String>> pending=null;
        try {
            var snapshot=json.createObjectNode().put("post_id",postId).put("title",title)
                    .put("content",content);
            snapshot.putArray("tags");
            var request=HttpRequest.newBuilder(origin.resolve("/internal/moderation-suggestion"))
                    .timeout(budget).header("Authorization","Bearer "+serviceToken)
                    .header("Accept","application/json").header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(snapshot.toString())).build();
            pending=client.sendAsync(request,info->new SearchResponseBody());
            var reply=pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if (reply.statusCode()!=200) throw unavailable();
            try {
                var parsed=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(reply.body());
                if (parsed==null || !parsed.isObject() || !parsed.path("status_code").isIntegralNumber()
                        || parsed.path("status_code").asInt()!=0) throw invalid();
                return parsed;
            } catch (java.io.IOException bad) { throw invalid(); }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (ExecutionException | TimeoutException error) { throw unavailable(); }
        finally { if (pending!=null && !pending.isDone()) pending.cancel(true); }
    }

    private ObjectNode project(JsonNode node,String postId) {
        String id=text(node,"suggestion_id",36), risk=text(node,"risk_level",16),
                action=text(node,"recommended_action",16), reason=text(node,"reason",500),
                policy=text(node,"policy_version",64), provider=text(node,"provider",64),
                trace=text(node,"trace_id",36);
        if (!uuid(id) || !RISKS.contains(risk) || !ACTIONS.contains(action) || reason.isBlank()
                || !uuid(trace) || !postId.equals(text(node,"post_id",18))) throw invalid();
        var result=json.createObjectNode().put("suggestion_id",id).put("post_id",postId)
                .put("risk_level",risk).put("recommended_action",action).put("reason",reason)
                .put("policy_version",policy).put("provider",provider)
                .put("latency_ms",number(node,"latency_ms",Long.MAX_VALUE)).put("trace_id",trace)
                .put("degraded",number(node,"degraded",1));
        result.putArray("rule_hits");
        for (JsonNode hit:node.path("rule_hits")) {
            if (!hit.isTextual() || !hit.asText().matches("[a-z_]{1,32}") || result.path("rule_hits").size()>=10) throw invalid();
            ((com.fasterxml.jackson.databind.node.ArrayNode)result.path("rule_hits")).add(hit.asText());
        }
        result.putArray("suggestions");
        for (JsonNode item:node.path("suggestions")) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length()>200
                    || result.path("suggestions").size()>=10) throw invalid();
            ((com.fasterxml.jackson.databind.node.ArrayNode)result.path("suggestions")).add(item.asText());
        }
        return result;
    }

    private static boolean uuid(String value) {
        return value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
    private static String text(JsonNode node,String key,int max) {
        var value=node.path(key); return value.isTextual()&&value.asText().length()<=max ? value.asText() : "";
    }
    private static long number(JsonNode node,String key,long max) {
        var value=node.path(key);
        if (!value.isIntegralNumber() || value.asLong()<0 || value.asLong()>max) throw invalid();
        return value.asLong();
    }
    private static ApiException invalid() { return new ApiException(502,"AGENT_SUGGESTION_INVALID","建议服务响应异常"); }
    private static ApiException unavailable() { return new ApiException(502,"AGENT_SUGGESTION_UNAVAILABLE","建议服务暂时不可用"); }
}
