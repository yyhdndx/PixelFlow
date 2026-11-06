package com.pixelflow.server.content;

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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class PostSummaryStreamController {
    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final URI base;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(4);
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(work -> {
        Thread thread = new Thread(work, "summary-stream-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public PostSummaryStreamController(ObjectMapper json, JdbcTemplate db,
            @Value("${pixelflow.intelligence-base-url:}") String origin) {
        this.json = json;
        this.db = db;
        base = origin.isBlank() ? null : URI.create(origin);
        if (base != null && (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))))
            throw new IllegalArgumentException("Intelligence URL must be an HTTP(S) origin");
    }

    @jakarta.annotation.PreDestroy
    void close() { timers.shutdownNow(); }

    private Map<String, Object> source(long id) {
        var rows = db.queryForList("SELECT p.title,p.content,p.content_version FROM posts p JOIN users u ON u.id=p.author_id WHERE p.id=? AND p.status='PUBLISHED' AND u.enabled=TRUE", id);
        if (rows.isEmpty()) throw new ApiException(404, "POST_NOT_FOUND", "作品不存在");
        return rows.get(0);
    }

    @GetMapping("/api/posts/{id}/summary/stream")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable long id, @AuthenticationPrincipal Actor actor,
            @RequestHeader("Authorization") String token) throws Exception {
        Map<String, Object> snapshot = source(id);
        String content = Objects.toString(snapshot.get("content"), "");
        if (content.strip().length() < 200) throw new ApiException(400, "SHORT_TEXT", "正文较短，不需要提炼");
        if (base == null || !slots.tryAcquire()) throw unavailable();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
        HttpResponse<InputStream> reply;
        try {
            var request = HttpRequest.newBuilder(base.resolve("/agent/posts/" + id + "/summary/stream"))
                    .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))
                    .header("X-Remaining-Budget-Ms", Long.toString(Math.max(1,
                            TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))))
                    .header("Authorization", token).GET().build();
            var opening = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
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
            String summary = null;
            List<String> points = new ArrayList<>();
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
                    if (!snapshot.equals(source(id))) throw new IOException("source changed");
                    ObjectNode safe = json.createObjectNode().put("seq", sequence);
                    if ("summary".equals(event)) {
                        if (summary != null || !node.path("summary").isTextual()) throw new IOException("invalid summary");
                        summary = node.path("summary").asText();
                        if (summary.isBlank() || summary.codePointCount(0, summary.length()) > 60) throw new IOException("invalid summary");
                        safe.put("summary", summary);
                    } else if ("point".equals(event)) {
                        String point = node.path("point").asText();
                        if (summary == null || !node.path("point").isTextual() || point.isBlank()
                                || point.codePointCount(0, point.length()) > 80 || points.size() >= 5) throw new IOException("invalid point");
                        points.add(point);
                        safe.put("point", point);
                    } else if ("done".equals(event)) {
                        if (summary == null || points.size() < 3 || !summary.equals(node.path("summary").asText())
                                || !json.valueToTree(points).equals(node.path("points")) || !node.path("validated").asBoolean()
                                || !node.path("trace_id").asText().matches("[0-9a-f-]{36}")) throw new IOException("invalid completion");
                        safe.put("summary", summary).put("validated", true).put("content_length", content.length());
                        safe.set("points", json.valueToTree(points));
                        safe.set("trace_id", node.get("trace_id"));
                    } else throw new IOException("upstream failure");
                    target.write(("event: " + event + "\ndata: " + safe + "\n\n").getBytes(StandardCharsets.UTF_8));
                    target.flush();
                    if ("done".equals(event)) break;
                }
            } catch (Exception failure) {
                ObjectNode error = json.createObjectNode().put("seq", ++sequence).put("code", "SUMMARY_INCOMPLETE")
                        .put("message", "提炼未完成，可以先读原文").put("incomplete", true);
                try { target.write(("event: error\ndata: " + error + "\n\n").getBytes(StandardCharsets.UTF_8)); target.flush(); }
                catch (IOException disconnected) { }
            } finally { timeout.cancel(false); release.run(); }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/event-stream;charset=UTF-8"))
                .header("Cache-Control", "no-store").header("X-Accel-Buffering", "no").body(output);
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

    private ApiException unavailable() { return new ApiException(503, "AI_UNAVAILABLE", "AI 提炼暂不可用，可以先读原文"); }
}
