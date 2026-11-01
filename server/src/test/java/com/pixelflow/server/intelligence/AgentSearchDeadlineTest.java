package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSearchDeadlineTest {
    final Actor actor=new Actor("alice","CREATOR","session");
    final AgentSearchController.SearchRequest request=new AgentSearchController.SearchRequest("摄影","");
    @Test void deadlineIncludesSlowBodyAfterHeaders() throws Exception {
        assertEquals(Duration.ofSeconds(55),AgentSearchController.BUDGET);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/agent/search",exchange->{
            exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(1500); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try {
            var controller=new AgentSearchController(new ObjectMapper(),mock(JdbcTemplate.class),"http://127.0.0.1:"+server.getAddress().getPort());
            long start=System.nanoTime();
            var error=assertThrows(ApiException.class,()->controller.search(request,actor,"Bearer token",Duration.ofMillis(150)));
            assertEquals(502,error.status);
            assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1200);
        } finally { server.stop(0); }
    }
    @Test void connectionFailureAndMissingConfigurationReturn502() throws Exception {
        int port; try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        for(String origin:new String[]{"","http://127.0.0.1:"+port}) {
            var controller=new AgentSearchController(new ObjectMapper(),mock(JdbcTemplate.class),origin);
            var error=assertThrows(ApiException.class,()->controller.search(request,actor,"Bearer token"));
            assertEquals(502,error.status); assertEquals("AGENT_SEARCH_UNAVAILABLE",error.code);
        }
    }
}
