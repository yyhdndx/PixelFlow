package com.pixelflow.server.copilot;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 调 Python Copilot 编排；任何失败返回 empty，界面只显示降级文案。 */
@Component
public class PythonCopilotAdvisor implements CopilotAdvisor {
    static final Duration BUDGET = Duration.ofSeconds(45);

    private final ObjectMapper json;
    private final URI origin;
    private final String serviceToken;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public PythonCopilotAdvisor(ObjectMapper json,
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
    public Optional<Result> chat(String screen, Map<String,Object> context, String message,
                                 List<Map<String,Object>> history) {
        if (origin == null) return Optional.empty();
        if (serviceToken.length()<32 || !serviceToken.chars().allMatch(c->c>=33 && c<=126))
            return Optional.empty();
        long deadline = System.nanoTime() + BUDGET.toNanos();
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            var payload = json.createObjectNode()
                    .put("screen", screen)
                    .put("message", message);
            payload.set("context", json.valueToTree(context));
            payload.set("history", json.valueToTree(history == null ? List.of() : history));
            var request = HttpRequest.newBuilder(origin.resolve("/internal/copilot"))
                    .timeout(BUDGET).header("Authorization","Bearer "+serviceToken)
                    .header("Accept","application/json").header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            pending = client.sendAsync(request,info -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8));
            var reply = pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if (reply.statusCode()!=200) return Optional.empty();
            var root = json.readTree(reply.body()).path("copilot");
            if (root.isMissingNode() || root.path("degraded").asInt(0)==1) return Optional.empty();
            String text = root.path("reply").asText("").trim();
            String traceId = root.path("trace_id").asText("");
            if (text.isEmpty()) return Optional.empty();
            var actions = new ArrayList<Map<String,Object>>();
            for (var item : root.path("actions")) {
                if (!item.isObject()) continue;
                actions.add(json.convertValue(item, Map.class));
            }
            var references = new ArrayList<Map<String,Object>>();
            for (var item : root.path("references")) {
                if (!item.isObject()) continue;
                String id = item.path("id").asText("");
                if (!id.matches("[1-9][0-9]{0,17}")) continue;
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("title", item.path("title").asText(""));
                row.put("excerpt", item.path("excerpt").asText(""));
                references.add(row);
                if (references.size() >= 8) break;
            }
            return Optional.of(new Result(text, actions, references, traceId));
        } catch (Exception error) {
            return Optional.empty();
        } finally {
            if (pending != null) pending.cancel(true);
        }
    }
}
