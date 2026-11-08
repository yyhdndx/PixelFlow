package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 流式创作辅助：转发 Python SSE（delta/done/error），校验 seq、单版候选与安全字段。 */
@RestController
public class CreationAssistStreamController {
    private final ObjectMapper json;
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(4);
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(work -> {
        Thread thread = new Thread(work, "assist-stream-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public CreationAssistStreamController(ObjectMapper json,
            @Value("${pixelflow.intelligence-base-url:}") String origin) {
        this.json = json;
        base = origin.isBlank() ? null : URI.create(origin);
        if (base != null && (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))))
            throw new IllegalArgumentException("Intelligence URL must be an HTTP(S) origin");
    }

    @jakarta.annotation.PreDestroy
    void close() { timers.shutdownNow(); }

    @PostMapping(value="/api/agent/create-assist/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(
            @RequestBody CreationAssistController.CreateAssistRequest body,
            @AuthenticationPrincipal Actor actor, @RequestHeader("Authorization") String token) throws Exception {
        if (!Set.of("improve_title", "suggest_tags", "polish_content", "write_content").contains(body.goal()))
            throw new ApiException(400, "INVALID_ARGUMENT", "流式创作仅支持单一目标");
        String prompt = body.prompt() == null ? "" : body.prompt().strip();
        if (body.draft().title().isBlank() && body.draft().content().isBlank() && prompt.isEmpty())
            throw new ApiException(400, "ASSIST_INPUT_REQUIRED", "先写一点内容，或告诉我你想写什么");
        if ("polish_content".equals(body.goal()) && body.draft().content().isBlank())
            throw new ApiException(400, "ASSIST_CONTENT_REQUIRED", "请先补充正文，或选择生成文案");
        if (body.previous_result() != null && prompt.isEmpty())
            throw new ApiException(400, "ASSIST_REVISION_REQUIRED", "请填写这次的修改要求");
        if (base == null || !slots.tryAcquire()) throw unavailable();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
        HttpResponse<InputStream> reply;
        try {
            var request = HttpRequest.newBuilder(base.resolve("/agent/create-assist/stream"))
                    .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))
                    .header("Authorization", token).header("Content-Type", "application/json")
                    .header("X-Remaining-Budget-Ms", Long.toString(Math.max(1,
                            TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var opening = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            try {
                reply = opening.get(Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(15),
                        deadline - System.nanoTime())), TimeUnit.NANOSECONDS);
            } catch (Exception failure) {
                opening.cancel(true);
                throw failure;
            }
            if (reply.statusCode() != 200) { reply.body().close(); throw unavailable(); }
        } catch (Exception failure) { slots.release(); throw unavailable(); }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) slots.release(); };
        var lastRead = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        var timeoutRef = new java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>>();
        var timeout = timers.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            if (now >= deadline || now - lastRead.get() >= TimeUnit.SECONDS.toNanos(15)) {
                try { reply.body().close(); } catch (IOException ignored) { }
                release.run();
                ScheduledFuture<?> scheduled = timeoutRef.get();
                if (scheduled != null) scheduled.cancel(false);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        timeoutRef.set(timeout);
        StreamingResponseBody output = target -> {
            StringBuilder text = new StringBuilder();
            long sequence = 0;
            int total = 0;
            try (InputStream input = reply.body()) {
                String event = null;
                while (true) {
                    String line = readLine(input);
                    if (line == null || System.nanoTime() >= deadline) throw new IOException("incomplete");
                    lastRead.set(System.nanoTime());
                    total += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (total > 262144) throw new IOException("oversized");
                    if (line.startsWith(":")) { target.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8)); target.flush(); continue; }
                    if (line.startsWith("event:")) { event = line.substring(6).trim(); continue; }
                    if (!line.startsWith("data:")) continue;
                    JsonNode node = json.readTree(line.substring(5).trim());
                    if (!node.isObject() || node.path("seq").asLong() != ++sequence) throw new IOException("invalid sequence");
                    ObjectNode safe = json.createObjectNode().put("seq", sequence);
                    if ("delta".equals(event)) {
                        if (!node.path("text").isTextual()) throw new IOException("invalid delta");
                        text.append(node.path("text").asText());
                        if (text.codePointCount(0, text.length()) > 5000) throw new IOException("content too long");
                        safe.put("text", node.get("text"));
                    } else if ("done".equals(event)) {
                        if (!validateDone(node, body.goal(), text.toString()))
                            throw new IOException("invalid completion");
                        for (String key : List.of("suggestion_id", "title_candidates", "content_candidates",
                                "tag_suggestions", "edit_suggestions", "safety", "provider", "policy_version", "validated"))
                            safe.set(key, node.get(key));
                    } else if ("error".equals(event)) {
                        safe.put("code", "ASSIST_INCOMPLETE").put("message", "AI 辅助创作未完成，请重试").put("incomplete", true);
                    } else throw new IOException("unknown event");
                    target.write(("event: " + event + "\ndata: " + safe + "\n\n").getBytes(StandardCharsets.UTF_8));
                    target.flush();
                    if ("done".equals(event) || "error".equals(event)) break;
                }
            } catch (Exception failure) {
                ObjectNode error = json.createObjectNode().put("seq", ++sequence).put("code", "ASSIST_INCOMPLETE")
                        .put("message", "AI 辅助创作未完成，请重试").put("incomplete", true);
                try { target.write(("event: error\ndata: " + error + "\n\n").getBytes(StandardCharsets.UTF_8)); target.flush(); }
                catch (IOException disconnected) { }
            } finally { timeout.cancel(false); release.run(); }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/event-stream;charset=UTF-8"))
                .header("Cache-Control", "no-store").header("X-Accel-Buffering", "no").body(output);
    }

    private boolean validateDone(JsonNode node, String goal, String text) {
        if (!node.path("suggestion_id").isTextual() || node.path("suggestion_id").asText().isBlank()
                || !node.path("safety").isObject() || !node.path("validated").asBoolean())
            return false;
        if (!List.of("deepseek", "rules").contains(node.path("provider").asText())) return false;
        for (String field : List.of("title_candidates", "content_candidates", "tag_suggestions", "edit_suggestions")) {
            if (!node.path(field).isArray() || node.path(field).size() > 20) return false;
            for (JsonNode value : node.path(field))
                if (!value.isTextual() || value.asText().length() > 5000) return false;
        }
        if (node.path("title_candidates").size() > 1 || node.path("content_candidates").size() > 1) return false;
        Set<String> allowed = switch (goal) {
            case "improve_title" -> Set.of("title_candidates");
            case "suggest_tags" -> Set.of("tag_suggestions");
            case "polish_content", "write_content" -> Set.of("content_candidates");
            default -> Set.of();
        };
        for (String field : List.of("title_candidates", "content_candidates", "tag_suggestions"))
            if (!allowed.contains(field) && !node.path(field).isEmpty()) return false;
        String primary = goal.equals("improve_title")
                ? node.path("title_candidates").path(0).asText()
                : goal.equals("suggest_tags") ? "" : node.path("content_candidates").path(0).asText();
        if (!primary.isEmpty() && !primary.equals(text)) return false;
        return true;
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return bytes.toString(StandardCharsets.UTF_8).stripTrailing();
            if (bytes.size() >= 16384) throw new IOException("oversized frame");
            bytes.write(value);
        }
        return null;
    }

    private ApiException unavailable() { return new ApiException(503, "ASSIST_UNAVAILABLE", "创作辅助服务暂时不可用"); }
}
