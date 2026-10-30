package com.pixelflow.server.ratelimit;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class ClientIpResolverTest {
    @Test void onlyExplicitProxyCanSupplySingleLiteralAddress() {
        var request=new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP","198.51.100.10");
        assertEquals("127.0.0.1",new ClientIpResolver(List.of()).resolve(request));
        var trusted=new ClientIpResolver(List.of("127.0.0.1/32"));
        assertEquals("198.51.100.10",trusted.resolve(request));
        request.removeHeader("X-Real-IP"); request.addHeader("X-Real-IP","localhost");
        assertEquals("127.0.0.1",trusted.resolve(request));
        request.removeHeader("X-Real-IP"); request.addHeader("X-Real-IP","198.51.100.10,198.51.100.11");
        assertEquals("127.0.0.1",trusted.resolve(request));
        request.removeHeader("X-Real-IP"); request.addHeader("X-Real-IP","198.51.100.10"); request.addHeader("X-Real-IP","198.51.100.11");
        assertEquals("127.0.0.1",trusted.resolve(request));
        request.removeHeader("X-Real-IP"); request.addHeader("X-Forwarded-For","198.51.100.10");
        assertEquals("127.0.0.1",trusted.resolve(request));
        request.addHeader("X-Real-IP","2001:db8::1");
        assertEquals("2001:db8:0:0:0:0:0:1",trusted.resolve(request));
        assertThrows(IllegalArgumentException.class,()->new ClientIpResolver(List.of("localhost")));
        assertThrows(IllegalArgumentException.class,()->new ClientIpResolver(List.of("127.0.0.1/33")));
    }
}
