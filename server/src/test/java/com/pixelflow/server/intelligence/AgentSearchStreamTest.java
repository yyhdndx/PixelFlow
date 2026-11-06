package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pixelflow.server.auth.AuthService.Actor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSearchStreamTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void forwardsPartialBeforeConfirmationAndUsesRecursiveCanonicalDigest() throws Exception {
        AtomicBoolean confirmed = new AtomicBoolean();
        String session = UUID.randomUUID().toString(), trace = UUID.randomUUID().toString();
        String canonical = "{\"answer\":\"正文〔1〕\",\"degraded\":0,\"session_id\":\"" + session
                + "\",\"sources\":[{\"author_name\":\"作者\",\"excerpt\":\"原文\",\"post_id\":\"1\",\"title\":\"标题\"}],\"trace_id\":\"" + trace + "\"}";
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/search/stream", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/confirm")) {
                var request = json.readTree(exchange.getRequestBody());
                confirmed.set(digest.equals(request.path("digest").asText()));
                byte[] response = ("{\"committed\":" + confirmed.get() + "}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else {
                String id = exchange.getRequestHeaders().getFirst("X-Request-Id");
                exchange.sendResponseHeaders(200, 0);
                var out = exchange.getResponseBody();
                ObjectNode metadata = json.createObjectNode().put("request_id", id).put("seq", 1)
                        .put("user_id", "alice").put("session_id", session).put("trace_id", trace).put("private_prompt", "secret");
                out.write(frame("meta", metadata));
                var sources = json.createArrayNode().addObject().put("post_id", "1").put("title", "标题")
                        .put("excerpt", "原文").put("author_name", "作者");
                ObjectNode selected = json.createObjectNode().put("request_id", id).put("seq", 2);
                selected.set("sources", json.createArrayNode().add(sources));
                out.write(frame("sources", selected));
                out.write(frame("delta", json.createObjectNode().put("request_id", id).put("seq", 3).put("text", "正文〔1〕")));
                out.flush();
                ObjectNode prepared = json.createObjectNode().put("request_id", id).put("seq", 4)
                        .put("answer", "正文〔1〕").put("session_id", session).put("trace_id", trace).put("degraded", 0);
                prepared.set("sources", selected.get("sources"));
                out.write(frame("prepared", prepared));
            }
            exchange.close();
        });
        server.start();
        var db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of(Map.of("title", "标题", "content", "原文内容", "nickname", "作者")));
        var controller = new AgentSearchStreamController(json, db, "http://127.0.0.1:" + server.getAddress().getPort());
        try {
            var response = controller.stream(new AgentSearchController.SearchRequest("问题", ""), new Actor("alice", "CREATOR", "session"), "Bearer token");
            AtomicBoolean sawPartial = new AtomicBoolean();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
                @Override public void flush() {
                    if (toString(StandardCharsets.UTF_8).contains("event: delta") && !confirmed.get()) sawPartial.set(true);
                }
            };
            response.getBody().writeTo(bytes);
            String content = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(sawPartial.get(), content);
            assertTrue(confirmed.get(), content);
            assertTrue(content.contains("event: done"), content);
            assertFalse(content.contains("secret"));
            assertFalse(content.contains("user_id"));
        } finally { controller.close(); server.stop(0); }
    }

    @Test void disconnectAndForeignIdentityNeverProduceDoneOrLeakErrorText() throws Exception {
        for (boolean foreign : new boolean[]{false, true}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/agent/search/stream", exchange -> {
                if (exchange.getRequestMethod().equals("DELETE")) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    String id = exchange.getRequestHeaders().getFirst("X-Request-Id");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(frame("meta", json.createObjectNode().put("request_id", id).put("seq", 1)
                            .put("user_id", foreign ? "other" : "alice")
                            .put("session_id", UUID.randomUUID().toString()).put("trace_id", UUID.randomUUID().toString())
                            .put("private_error", "internal-key")));
                }
                exchange.close();
            });
            server.start();
            var controller = new AgentSearchStreamController(json, mock(JdbcTemplate.class), "http://127.0.0.1:" + server.getAddress().getPort());
            try {
                var response = controller.stream(new AgentSearchController.SearchRequest("问题", ""), new Actor("alice", "CREATOR", "session"), "Bearer token");
                var bytes = new ByteArrayOutputStream();
                response.getBody().writeTo(bytes);
                String content = bytes.toString(StandardCharsets.UTF_8);
                assertTrue(content.contains("event: error"), content);
                assertFalse(content.contains("event: done"));
                assertFalse(content.contains("internal-key"));
            } finally { controller.close(); server.stop(0); }
        }
    }

    @Test void withdrawnSourcesAndOversizedFramesAreCancelledWithoutCompletion() throws Exception {
        for (boolean oversized : new boolean[]{false, true}) {
            var cancelled = new java.util.concurrent.CountDownLatch(1);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/agent/search/stream", exchange -> {
                if (exchange.getRequestMethod().equals("DELETE")) {
                    cancelled.countDown();
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    String id = exchange.getRequestHeaders().getFirst("X-Request-Id");
                    assertTrue(Long.parseLong(exchange.getRequestHeaders().getFirst("X-Remaining-Budget-Ms")) <= 55000);
                    exchange.sendResponseHeaders(200, 0);
                    var out = exchange.getResponseBody();
                    out.write(frame("meta", json.createObjectNode().put("request_id", id).put("seq", 1)
                            .put("user_id", "alice").put("session_id", UUID.randomUUID().toString())
                            .put("trace_id", UUID.randomUUID().toString())));
                    if (oversized) out.write(("data: " + "x".repeat(16385) + "\n\n").getBytes(StandardCharsets.UTF_8));
                    else {
                        var source = json.createObjectNode().put("post_id", "1").put("title", "标题")
                                .put("excerpt", "原文").put("author_name", "作者");
                        var selected = json.createObjectNode().put("request_id", id).put("seq", 2);
                        selected.set("sources", json.createArrayNode().add(source));
                        out.write(frame("sources", selected));
                    }
                }
                exchange.close();
            });
            server.start();
            var db = mock(JdbcTemplate.class);
            when(db.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
            var controller = new AgentSearchStreamController(json, db, "http://127.0.0.1:" + server.getAddress().getPort());
            try {
                var response = controller.stream(new AgentSearchController.SearchRequest("问题", ""),
                        new Actor("alice", "CREATOR", "session"), "Bearer token");
                var bytes = new ByteArrayOutputStream();
                response.getBody().writeTo(bytes);
                String content = bytes.toString(StandardCharsets.UTF_8);
                assertTrue(content.contains("event: error"));
                assertFalse(content.contains("event: done"));
                assertFalse(content.contains("event: sources"));
                assertTrue(cancelled.await(3, java.util.concurrent.TimeUnit.SECONDS));
            } finally { controller.close(); server.stop(0); }
        }
    }

    @Test void silentResponseHeadersAreTimedOutBeforeOverallBudget() throws Exception {
        var accepted = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/search/stream", exchange -> {
            accepted.countDown();
            try { finish.await(20, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var controller = new AgentSearchStreamController(json, mock(JdbcTemplate.class),
                "http://127.0.0.1:" + server.getAddress().getPort());
        long start = System.nanoTime();
        try {
            var failure = assertThrows(com.pixelflow.server.common.ApiException.class,
                    () -> controller.stream(new AgentSearchController.SearchRequest("问题", ""),
                            new Actor("alice", "CREATOR", "session"), "Bearer token"));
            assertTrue(accepted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 20);
        } finally { finish.countDown(); controller.close(); server.stop(0); }
    }

    @Test void clientDisconnectCancelsUpstreamWithoutCompleting() throws Exception {
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/search/stream", exchange -> {
            if (exchange.getRequestMethod().equals("DELETE")) {
                cancelled.countDown();
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
            }
            exchange.close();
        });
        server.start();
        var controller = new AgentSearchStreamController(json, mock(JdbcTemplate.class),
                "http://127.0.0.1:" + server.getAddress().getPort());
        try {
            var response = controller.stream(new AgentSearchController.SearchRequest("问题", ""),
                    new Actor("alice", "CREATOR", "session"), "Bearer token");
            response.getBody().writeTo(new java.io.OutputStream() {
                public void write(int value) throws java.io.IOException { throw new java.io.IOException("closed client"); }
            });
            assertTrue(cancelled.await(3, java.util.concurrent.TimeUnit.SECONDS));
        } finally { controller.close(); server.stop(0); }
    }

    @Test void heartbeatCannotExtendDeadlineAndTotalBytesAreBounded() throws Exception {
        for (boolean oversized : new boolean[]{true, false}) {
            var cancelled = new java.util.concurrent.CountDownLatch(1);
            var workers = java.util.concurrent.Executors.newCachedThreadPool();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/agent/search/stream", exchange -> {
                if (exchange.getRequestMethod().equals("DELETE")) {
                    cancelled.countDown();
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        byte[] heartbeat = (": " + "x".repeat(oversized ? 8000 : 1) + "\n\n").getBytes(StandardCharsets.UTF_8);
                        for (int i = 0; i < (oversized ? 40 : 70); i++) {
                            exchange.getResponseBody().write(heartbeat);
                            exchange.getResponseBody().flush();
                            if (!oversized) Thread.sleep(1000);
                        }
                    } catch (java.io.IOException disconnected) {
                    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                exchange.close();
            });
            server.start();
            var controller = new AgentSearchStreamController(json, mock(JdbcTemplate.class),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            long start = System.nanoTime();
            try {
                var response = controller.stream(new AgentSearchController.SearchRequest("问题", ""),
                        new Actor("alice", "CREATOR", "session"), "Bearer token");
                var bytes = new ByteArrayOutputStream();
                response.getBody().writeTo(bytes);
                long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                String content = bytes.toString(StandardCharsets.UTF_8);
                assertTrue(content.contains("event: error"), content);
                assertFalse(content.contains("event: done"));
                if (!oversized) assertTrue(elapsed >= 54000 && elapsed < 59000, "elapsed=" + elapsed);
                assertTrue(cancelled.await(3, java.util.concurrent.TimeUnit.SECONDS));
            } finally { controller.close(); server.stop(0); workers.shutdownNow(); }
        }
    }

    private byte[] frame(String event, ObjectNode data) {
        return ("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
