package com.pixelflow.server.comment;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 调 Python Agent 判定场景。模型不可用时不自动回复：这里任何失败都返回 empty，
 * 由 CommentController 决定“这条评论不自动回”，不会用关键词猜一个答案。
 */
@Component
public class PythonCommentReplyAdvisor implements CommentReplyAdvisor {
    static final Duration BUDGET = Duration.ofSeconds(4);

    private final ObjectMapper json;
    private final URI origin;
    private final String serviceToken;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public PythonCommentReplyAdvisor(ObjectMapper json,
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
    public Optional<Match> decide(long postId,String title,String content,String comment,List<Rule> rules) {
        if (origin==null || rules.isEmpty()) return Optional.empty();
        if (serviceToken.length()<32 || !serviceToken.chars().allMatch(c->c>=33 && c<=126))
            return Optional.empty();
        long deadline = System.nanoTime() + BUDGET.toNanos();
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            var payload = json.createObjectNode()
                    .put("post_id", Long.toString(postId))
                    .put("title", title == null ? "" : title)
                    .put("content", content == null ? "" : content)
                    .put("comment", comment);
            var array = payload.putArray("rules");
            for (Rule rule : rules) {
                array.addObject().put("question", rule.question()).put("answer", rule.answer());
            }
            var request = HttpRequest.newBuilder(origin.resolve("/internal/comment-reply"))
                    .timeout(BUDGET).header("Authorization","Bearer "+serviceToken)
                    .header("Accept","application/json").header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            pending = client.sendAsync(request,info -> HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            var reply = pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if (reply.statusCode()!=200) return Optional.empty();
            var root = json.readTree(reply.body());
            var decision = root.path("reply");
            if (decision.isMissingNode()) return Optional.empty();
            if (decision.path("degraded").asInt(0)==1) return Optional.empty();
            int index = decision.path("match_index").isNumber() ? decision.path("match_index").asInt() : -1;
            if (index < 0 || index >= rules.size()) return Optional.empty();
            Rule rule = rules.get(index);
            return Optional.of(new Match(index, rule.answer(),
                    decision.path("reason").asText(""),
                    decision.path("trace_id").asText("")));
        } catch (Exception e) {
            return Optional.empty();
        } finally {
            if (pending != null) pending.cancel(true);
        }
    }
}
