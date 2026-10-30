package com.pixelflow.server.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitProperties properties;
    private final RedisRateLimiter limiter;
    private final ClientIpResolver ips;
    private final ObjectMapper json;

    public RateLimitFilter(RateLimitProperties properties, RedisRateLimiter limiter, ClientIpResolver ips, ObjectMapper json) {
        this.properties=properties; this.limiter=limiter; this.ips=ips; this.json=json;
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if (!properties.enabled() || !request.getMethod().equals("POST")) { chain.doFilter(request,response); return; }
        String path=request.getServletPath();
        List<RedisRateLimiter.Attempt> attempts=new ArrayList<>();
        HttpServletRequest downstream=request;
        long ipRetry=0;
        if (path.equals("/api/auth/login")) {
            String ip=ips.resolve(request);
            ipRetry=limiter.count(List.of(new RedisRateLimiter.Attempt("login-ip",properties.loginIp(),List.of(ip))));
            byte[] bytes=request.getInputStream().readAllBytes();
            downstream=new ReplayRequest(request,bytes);
            try {
                var body=json.readTree(bytes);
                var username=body==null ? null : body.get("username");
                if (username!=null && username.isTextual()) attempts.add(new RedisRateLimiter.Attempt(
                        "login-account",properties.loginAccount(),List.of(ip,username.asText().toLowerCase(Locale.ROOT))));
            } catch (IOException ignored) { }
        } else if (path.equals("/api/auth/register")) {
            attempts.add(new RedisRateLimiter.Attempt("register-ip",properties.registerIp(),List.of(ips.resolve(request))));
        } else if (path.equals("/api/media")) {
            var authentication=SecurityContextHolder.getContext().getAuthentication();
            if (authentication!=null && authentication.getPrincipal() instanceof Actor actor)
                attempts.add(new RedisRateLimiter.Attempt("upload-user",properties.uploadUser(),List.of(actor.id())));
        }
        long otherRetry=limiter.count(attempts);
        boolean failed=ipRetry<0 || otherRetry<0;
        if (path.equals("/api/auth/login") || !attempts.isEmpty()) limiter.recordOutcome(failed);
        long retry=failed ? 0 : Math.max(ipRetry,otherRetry);
        if (retry>0) {
            response.setStatus(429); response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After",Long.toString(retry)); response.setHeader("Cache-Control","no-store");
            json.writeValue(response.getWriter(),ApiErrors.body(429,"RATE_LIMITED","请求过于频繁，请稍后重试",request.getAttribute("requestId")));
            return;
        }
        chain.doFilter(downstream,response);
    }

    private static final class ReplayRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        ReplayRequest(HttpServletRequest request,byte[] bytes) { super(request); this.bytes=bytes; }
        @Override public ServletInputStream getInputStream() {
            var input=new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] buffer,int offset,int length) { return input.read(buffer,offset,length); }
                @Override public boolean isFinished() { return input.available()==0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(),getCharacterEncoding()==null
                    ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }
}
