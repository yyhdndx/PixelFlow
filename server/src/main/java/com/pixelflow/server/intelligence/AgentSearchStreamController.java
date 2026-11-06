package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@RestController
public class AgentSearchStreamController {
    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(8);
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-stream-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    @jakarta.annotation.PreDestroy
    void close() { watchdog.shutdownNow(); }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            new TreeSet<String>(iterableFields(value)).forEach(key -> sorted.set(key, canonical(value.get(key))));
            return sorted;
        }
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(child -> array.add(canonical(child)));
            return array;
        }
        return value;
    }

    private Set<String> iterableFields(JsonNode value) {
        Set<String> fields = new HashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    public AgentSearchStreamController(ObjectMapper json, JdbcTemplate db,
            @Value("${pixelflow.intelligence-base-url:}") String origin) {
        this.json = json;
        this.db = db;
        base = origin.isBlank() ? null : URI.create(origin);
        if (base != null && (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))))
            throw new IllegalArgumentException("Intelligence URL must be an HTTP(S) origin");
    }

    @PostMapping(value="/api/agent/search/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody AgentSearchController.SearchRequest body,
            @AuthenticationPrincipal Actor actor, @RequestHeader("Authorization") String token) throws Exception {
        if (body.question() == null || body.question().isBlank()
                || body.question().codePointCount(0, body.question().length()) > 200
                || (body.session_id() != null && !body.session_id().isEmpty()
                    && !body.session_id().matches(AgentSearchController.UUID_PATTERN)))
            throw new ApiException(400, "INVALID_ARGUMENT", "问题或会话格式无效");
        if (base == null || !slots.tryAcquire()) throw unavailable();
        String requestId = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
        HttpResponse<InputStream> response;
        try {
            var request = HttpRequest.newBuilder(base.resolve("/agent/search/stream"))
                    .timeout(Duration.ofSeconds(55)).header("Authorization", token)
                    .header("Content-Type", "application/json").header("X-Request-Id", requestId)
                    .header("X-Remaining-Budget-Ms", "45000")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) { response.body().close(); throw unavailable(); }
        } catch (Exception error) { slots.release(); throw unavailable(); }
        var lastRead = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) slots.release(); };
        var expiryRef = new java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>>();
        ScheduledFuture<?> expiry = watchdog.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            if (now >= deadline || now - lastRead.get() >= TimeUnit.SECONDS.toNanos(15)) {
                try { response.body().close(); } catch (IOException ignored) { }
                release.run();
                ScheduledFuture<?> scheduled = expiryRef.get();
                if (scheduled != null) scheduled.cancel(false);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        expiryRef.set(expiry);
        StreamingResponseBody output = stream -> {
            long seq = 0;
            int total = 0;
            StringBuilder answer = new StringBuilder();
            JsonNode sources = null;
            boolean completed = false;
            String sessionId = null, traceId = null;
            try (InputStream input = response.body()) {
                String event = null;
                while (true) {
                    if (System.nanoTime() >= deadline) throw new IOException("deadline");
                    String line = line(input);
                    if (line == null) throw new IOException("incomplete stream");
                    lastRead.set(System.nanoTime());
                    total += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (total > 262144) throw new IOException("oversized stream");
                    if (line.startsWith(":")) {
                        stream.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                        stream.flush();
                        continue;
                    }
                    if (line.startsWith("event:")) { event = line.substring(6).trim(); continue; }
                    if (!line.startsWith("data:")) continue;
                    lastRead.set(System.nanoTime());
                    JsonNode node = json.readTree(line.substring(5).trim());
                    if (!node.isObject() || !requestId.equals(node.path("request_id").asText())
                            || !node.path("seq").isIntegralNumber() || node.path("seq").asLong() != ++seq)
                        throw new IOException("invalid identity");
                    ObjectNode safe = json.createObjectNode().put("request_id", requestId).put("seq", seq);
                    if ("meta".equals(event)) {
                        if (seq != 1 || !actor.id().equals(node.path("user_id").asText())) throw new IOException("identity mismatch");
                        for (String key : List.of("session_id", "trace_id")) {
                            if (!node.path(key).asText().matches(AgentSearchController.UUID_PATTERN)) throw new IOException("invalid metadata");
                            safe.set(key, node.get(key));
                        }
                        sessionId = node.path("session_id").asText();
                        traceId = node.path("trace_id").asText();
                        if (body.session_id() != null && !body.session_id().isEmpty()
                                && !body.session_id().equals(sessionId)) throw new IOException("session mismatch");
                    } else if (seq == 1) {
                        throw new IOException("missing metadata");
                    } else if ("sources".equals(event)) {
                        if (sources != null) throw new IOException("duplicate sources");
                        sources = node.path("sources");
                        validateSources(sources);
                        safe.set("sources", sources);
                    } else if ("delta".equals(event)) {
                        if (sources == null || !node.path("text").isTextual()) throw new IOException("invalid delta");
                        answer.append(node.path("text").asText());
                        if (answer.codePointCount(0, answer.length()) > 1500) throw new IOException("answer too long");
                        safe.set("text", node.get("text"));
                    } else if ("prepared".equals(event)) {
                        if (sources == null || !sources.equals(node.path("sources"))
                                || !answer.toString().equals(node.path("answer").asText())) throw new IOException("result mismatch");
                        if (!Objects.equals(sessionId, node.path("session_id").asText())
                                || !Objects.equals(traceId, node.path("trace_id").asText())) throw new IOException("changed metadata");
                        if (!node.path("degraded").isInt() || node.path("degraded").asInt() < 0 || node.path("degraded").asInt() > 1)
                            throw new IOException("invalid degradation");
                        validateSources(sources);
                        validateAnswer(answer.toString(), sources.size());
                        ObjectNode result = json.createObjectNode();
                        for (String key : List.of("answer", "sources", "session_id", "trace_id", "degraded")) {
                            result.set(key, node.get(key));
                            safe.set(key, node.get(key));
                        }
                        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(json.writeValueAsBytes(canonical(result))));
                        var confirmation = HttpRequest.newBuilder(base.resolve("/agent/search/stream/confirm"))
                                .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))
                                .header("Authorization", token).header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                                        "request_id", requestId, "trace_id", node.path("trace_id").asText(), "digest", hash)))).build();
                        var confirmed = http.send(confirmation, HttpResponse.BodyHandlers.ofInputStream());
                        ScheduledFuture<?> confirmationExpiry = watchdog.schedule(() -> {
                            try { confirmed.body().close(); } catch (IOException ignored) { }
                        }, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                        try (InputStream confirmationBody = confirmed.body()) {
                            byte[] bytes = confirmationBody.readNBytes(4097);
                            if (System.nanoTime() >= deadline || bytes.length > 4096 || confirmed.statusCode() != 200
                                    || !json.readTree(bytes).path("committed").asBoolean())
                                throw new IOException("confirmation failed");
                        } finally { confirmationExpiry.cancel(false); }
                        safe.put("validated", true);
                        write(stream, "done", safe);
                        completed = true;
                        break;
                    } else if ("phase".equals(event)) {
                        if (!Set.of("retrieving", "generating", "validating").contains(node.path("phase").asText())) throw new IOException("invalid phase");
                        safe.set("phase", node.get("phase"));
                    } else if ("error".equals(event)) {
                        safe.put("code", "AGENT_SEARCH_INCOMPLETE").put("message", "AI 搜索未完成，请重试").put("incomplete", true);
                    } else throw new IOException("unknown event");
                    write(stream, event, safe);
                    if ("error".equals(event)) break;
                }
            } catch (Exception error) {
                ObjectNode failure = json.createObjectNode();
                failure.put("request_id", requestId).put("seq", ++seq).put("code", "AGENT_SEARCH_INCOMPLETE")
                        .put("message", "AI 搜索未完成，请重试").put("incomplete", true);
                try { write(stream, "error", failure); } catch (IOException disconnected) { }
            } finally {
                expiry.cancel(false);
                release.run();
                if (!completed) {
                    var cancel = HttpRequest.newBuilder(base.resolve("/agent/search/stream/" + requestId))
                            .timeout(Duration.ofSeconds(2)).header("Authorization", token).DELETE().build();
                    http.sendAsync(cancel, HttpResponse.BodyHandlers.discarding());
                }
            }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/event-stream;charset=UTF-8"))
                .header("Cache-Control", "no-store").header("X-Accel-Buffering", "no").body(output);
    }

    private void validateSources(JsonNode sources) throws IOException {
        if (!sources.isArray() || sources.size() > 5) throw new IOException("invalid sources");
        Set<String> seen = new HashSet<>();
        for (JsonNode source : sources) {
            if (!source.isObject() || source.size() != 4) throw new IOException("invalid source fields");
            for (String key : List.of("post_id", "title", "excerpt", "author_name"))
                if (!source.path(key).isTextual()) throw new IOException("invalid source field");
            if (source.path("title").asText().length() > 240 || source.path("excerpt").asText().length() > 2000
                    || source.path("author_name").asText().length() > 128) throw new IOException("oversized source");
            String id = source.path("post_id").asText();
            if (!id.matches("[1-9][0-9]{0,17}") || !seen.add(id)) throw new IOException("invalid source id");
            var rows = db.queryForList("SELECT p.title,p.content,u.nickname FROM posts p JOIN users u ON u.id=p.author_id WHERE p.id=? AND p.status='PUBLISHED' AND u.enabled=TRUE", Long.parseLong(id));
            if (rows.isEmpty() || !Objects.equals(rows.get(0).get("title"), source.path("title").asText())
                    || !((String) rows.get(0).get("content")).startsWith(source.path("excerpt").asText())
                    || !Objects.equals(rows.get(0).get("nickname"), source.path("author_name").asText()))
                throw new IOException("source changed");
        }
    }
    private void validateAnswer(String answer, int count) throws IOException {
        if (answer.isBlank()) throw new IOException("empty answer");
        var refs = java.util.regex.Pattern.compile("〔([^〕]*)〕").matcher(answer);
        boolean cited = false;
        while (refs.find()) {
            if (!refs.group(1).matches("[1-5]") || Integer.parseInt(refs.group(1)) > count) throw new IOException("invalid citation");
            cited = true;
        }
        String withoutReferences = answer.replaceAll("〔[^〕]*〕", "");
        if (withoutReferences.contains("〔") || withoutReferences.contains("〕")) throw new IOException("malformed citation");
        if (count > 0 && !cited) throw new IOException("missing citations");
    }
    private String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return bytes.toString(StandardCharsets.UTF_8).stripTrailing();
            if (bytes.size() >= 16384) throw new IOException("frame too large");
            bytes.write(value);
        }
        return null;
    }
    private void write(OutputStream output, String event, JsonNode data) throws IOException {
        output.write(("event: " + event + "\ndata: " + json.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
    private ApiException unavailable() { return new ApiException(502, "AGENT_SEARCH_UNAVAILABLE", "AI 搜索暂不可用，普通搜索仍可使用"); }
}
