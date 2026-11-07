package com.pixelflow.server.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.auth.AuthService.Actor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostSummaryStreamTest {
    @Test void forwardsSummaryThenPointsAndRejectsVersionChanges() throws Exception {
        for (boolean changed : new boolean[]{false, true}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/agent/posts/1/summary/stream", exchange -> {
                String content = "event: summary\ndata: {\"seq\":1,\"summary\":\"概括\"}\n\n";
                for (int i = 0; i < 3; i++) content += "event: point\ndata: {\"seq\":" + (i + 2) + ",\"point\":\"要点" + i + "\"}\n\n";
                content += "event: done\ndata: {\"seq\":5,\"summary\":\"概括\",\"points\":[\"要点0\",\"要点1\",\"要点2\"],\"trace_id\":\"" + UUID.randomUUID() + "\",\"validated\":true}\n\n";
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            var db = mock(JdbcTemplate.class);
            Map<String, Object> snapshot = Map.of("title", "标题", "content", "长文".repeat(150), "content_version", 1L);
            var row = List.of(snapshot);
            if (changed) when(db.queryForList(anyString(), any(Object[].class))).thenReturn(row, row,
                    List.of(Map.of("title", "标题", "content", "修改".repeat(150), "content_version", 2L)));
            else when(db.queryForList(anyString(), any(Object[].class))).thenReturn(row);
            var controller = new PostSummaryStreamController(new ObjectMapper(), db, "http://127.0.0.1:" + server.getAddress().getPort());
            try {
                var response = controller.stream(1, new Actor("alice", "CREATOR", "session"), "Bearer test");
                var output = new ByteArrayOutputStream();
                response.getBody().writeTo(output);
                String text = output.toString(StandardCharsets.UTF_8);
                assertTrue(text.contains("event: summary"), text);
                assertEquals(!changed, text.contains("event: done"), text);
                assertEquals(changed, text.contains("event: error"), text);
            } finally { controller.close(); server.stop(0); }
        }
    }
}
