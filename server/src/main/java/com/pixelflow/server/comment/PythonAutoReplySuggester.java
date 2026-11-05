package com.pixelflow.server.comment;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 调 Python Agent 提炼规则；任何失败都返回 empty，界面退回手动填写。 */
@Component
public class PythonAutoReplySuggester implements AutoReplySuggester {
    static final Duration BUDGET = Duration.ofSeconds(25);

    private final ObjectMapper json;
    private final URI origin;
    private final String serviceToken;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public PythonAutoReplySuggester(ObjectMapper json,
            @Value("${pixelflow.intelligence-base-url:}") String base,
            @Value("${PIXELFLOW_INTELLIGENCE_TOKEN:}") String token) {
        this.json = json; this.serviceToken = token;
        if (base.isBlank()) { origin = null; return; }
        URI uri = URI.create(base);
        if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalStateException("Intelligence URL must be an HTTP(S) origin");
        origin = uri;
    }

    @Override
    public Optional<Result> suggest(long postId,String title,String content,String prompt) {
        if (origin == null) return Optional.empty();
        if (serviceToken.length()<32 || !serviceToken.chars().allMatch(c->c>=33 && c<=126))
            return Optional.empty();
        long deadline = System.nanoTime() + BUDGET.toNanos();
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            var payload = json.createObjectNode()
                    .put("post_id", Long.toString(postId))
                    .put("title", title == null ? "" : title)
                    .put("content", content == null ? "" : content)
                    .put("prompt", prompt);
            var request = HttpRequest.newBuilder(origin.resolve("/internal/auto-reply-suggest"))
                    .timeout(BUDGET).header("Authorization","Bearer "+serviceToken)
                    .header("Accept","application/json").header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            pending = client.sendAsync(request,info -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8));
            var reply = pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if (reply.statusCode()!=200) return Optional.empty();
            var root = json.readTree(reply.body()).path("suggestion");
            if (root.isMissingNode()) return Optional.empty();
            var rules = new ArrayList<Rule>();
            for (var item : root.path("suggestions")) {
                String question = item.path("question").asText("").trim();
                String answer = item.path("answer").asText("").trim();
                if (!question.isEmpty() && !answer.isEmpty()) rules.add(new Rule(question,answer));
            }
            return Optional.of(new Result(rules, root.path("degraded").asInt(0)==1,
                    root.path("reason").asText(""), root.path("trace_id").asText("")));
        } catch (Exception e) {
            return Optional.empty();
        } finally {
            if (pending != null) pending.cancel(true);
        }
    }
}
