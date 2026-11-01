package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.databind.*;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@RestController
public class AgentSearchController {
    static final String UUID_PATTERN="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    static final Duration BUDGET=Duration.ofSeconds(55);
    public record SearchRequest(@NotBlank String question,
            @Pattern(regexp="^$|^"+UUID_PATTERN+"$") String session_id) {}
    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final URI endpoint;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public AgentSearchController(ObjectMapper json, JdbcTemplate db,
            @Value("${pixelflow.intelligence-base-url:}") String base) {
        this.json=json; this.db=db;
        if (base.isBlank()) { endpoint=null; return; }
        URI uri=URI.create(base);
        if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalStateException("Intelligence URL must be an HTTP(S) origin");
        endpoint=uri.resolve("/agent/search");
    }

    @PostMapping("/api/agent/search")
    public ResponseEntity<JsonNode> search(@Valid @RequestBody SearchRequest body,
            @AuthenticationPrincipal Actor actor, @RequestHeader("Authorization") String token) {
        return search(body,actor,token,BUDGET);
    }

    // Package-visible budget allows deterministic deadline tests without a 55-second sleep.
    ResponseEntity<JsonNode> search(SearchRequest body, Actor actor, String token, Duration budget) {
        if (body.question()==null || body.question().isBlank() || body.question().codePointCount(0,body.question().length())>200)
            throw new ApiException(400,"INVALID_ARGUMENT","问题应为 1–200 字且不得为空白");
        if (endpoint==null) throw unavailable();
        long deadline=System.nanoTime()+budget.toNanos();
        CompletableFuture<HttpResponse<String>> pending=null;
        JsonNode result;
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(budget)
                    .header("Authorization",token).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                            "question",body.question(),"session_id",body.session_id()==null ? "" : body.session_id())))).build();
            pending=client.sendAsync(request,info -> new SearchResponseBody());
            var response=pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if (response.statusCode()!=200) throw unavailable();
            try { result=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body()); }
            catch (java.io.IOException invalid) { throw invalid(); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (ExecutionException | TimeoutException | java.io.IOException error) {
            throw unavailable();
        } finally { if (pending!=null && !pending.isDone()) pending.cancel(true); }
        validate(result,actor,body.session_id());
        var output=json.createObjectNode();
        output.put("status_code",0);
        output.put("answer",result.path("sources").isEmpty() ? "社区内暂未找到相关作品，可以换个说法再试。" : result.path("answer").asText());
        for (String field:List.of("sources","session_id","trace_id","degraded")) output.set(field,result.get(field));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(output);
    }

    private void validate(JsonNode result, Actor actor, String session) {
        if (result==null || !result.isObject() || !actor.id().equals(text(result,"user_id",36))) throw invalid();
        String answer=text(result,"answer",1500);
        if (answer.isBlank()) throw invalid();
        for (String key:List.of("session_id","trace_id")) if (!text(result,key,36).matches(UUID_PATTERN)) throw invalid();
        if (session!=null && !session.isEmpty() && !session.equals(result.get("session_id").asText())) throw invalid();
        if (!result.path("degraded").isIntegralNumber() || !Set.of("0","1").contains(result.path("degraded").asText())) throw invalid();
        var sources=result.path("sources");
        if (!sources.isArray() || sources.size()>5) throw invalid();
        Set<String> seen=new HashSet<>();
        for (JsonNode source:sources) {
            if (!source.isObject() || source.size()!=4) throw invalid();
            String id=text(source,"post_id",18), title=text(source,"title",120);
            String excerpt=text(source,"excerpt",100), author=text(source,"author_name",64);
            if (!id.matches("[1-9][0-9]{0,17}") || !seen.add(id)) throw invalid();
            var rows=db.queryForList("SELECT p.title,p.content,u.nickname FROM posts p JOIN users u ON u.id=p.author_id WHERE p.id=? AND p.status='PUBLISHED' AND u.enabled=TRUE",Long.parseLong(id));
            // Reject the entire answer: removing one source would leave its text/citations behind.
            if (rows.isEmpty() || !title.equals(rows.get(0).get("title"))
                    || !((String)rows.get(0).get("content")).startsWith(excerpt)
                    || !author.equals(rows.get(0).get("nickname"))) throw invalid();
        }
        if (!sources.isEmpty()) {
            var refs=java.util.regex.Pattern.compile("〔([^〕]*)〕").matcher(answer);
            boolean cited=false;
            while (refs.find()) {
                String number=refs.group(1);
                if (!number.matches("[1-5]") || Integer.parseInt(number)>sources.size()) throw invalid();
                cited=true;
            }
            if (!cited) throw invalid();
        }
    }
    private String text(JsonNode node,String key,int max) {
        JsonNode value=node.path(key);
        if (!value.isTextual() || value.asText().codePointCount(0,value.asText().length())>max) throw invalid();
        return value.asText();
    }
    private static ApiException unavailable() { return new ApiException(502,"AGENT_SEARCH_UNAVAILABLE","AI 搜索暂时不可用，请稍后重试，普通搜索仍可使用"); }
    private static ApiException invalid() { return new ApiException(502,"INVALID_AGENT_SEARCH_RESPONSE","AI 搜索结果无效或来源已变化，请重新搜索"); }
}
