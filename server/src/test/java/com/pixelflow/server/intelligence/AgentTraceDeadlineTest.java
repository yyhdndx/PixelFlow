package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class AgentTraceDeadlineTest {
    @Test void fullBodyDeadlineIsBounded() throws Exception {
        assertEquals(Duration.ofSeconds(10),AgentTraceController.BUDGET);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/traces",exchange->{
            exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(1500); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try {
            var controller=new AgentTraceController(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"x".repeat(32));
            long start=System.nanoTime();
            var error=assertThrows(ApiException.class,()->controller.fetch("/internal/traces",false,Duration.ofMillis(150)));
            assertEquals(502,error.status); assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1200);
        } finally { server.stop(0); }
    }
    @Test void missingConfigAndRefusedConnectionFailWithoutData() throws Exception {
        int port; try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        for(String[] config:new String[][]{{"","x".repeat(32)},{"http://127.0.0.1:"+port,""},{"http://127.0.0.1:"+port,"x".repeat(32)}}) {
            var controller=new AgentTraceController(new ObjectMapper(),config[0],config[1]);
            assertEquals(502,assertThrows(ApiException.class,()->controller.fetch("/internal/traces",false,AgentTraceController.BUDGET)).status);
        }
    }
}
