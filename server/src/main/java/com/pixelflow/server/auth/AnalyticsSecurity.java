package com.pixelflow.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.common.ApiErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.io.IOException;

@Configuration
public class AnalyticsSecurity {
    @Bean @Order(1)
    SecurityFilterChain analyticsFilterChain(HttpSecurity http,ObjectMapper json,
            @Value("${pixelflow.analytics-export-token:}") String secret) throws Exception {
        if (!secret.isEmpty() && secret.length()<32) throw new IllegalStateException("Analytics export token must be at least 32 characters");
        return http.securityMatcher("/api/internal/**").csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable())
                .authorizeHttpRequests(r -> r.requestMatchers(HttpMethod.GET,"/api/internal/analytics/events")
                        .hasAuthority("events:read").anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((req,res,err) -> error(json,req,res,401))
                        .accessDeniedHandler((req,res,err) -> error(json,req,res,403)))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
                            throws ServletException,IOException {
                        String requestId=UUID.randomUUID().toString();
                        req.setAttribute("requestId",requestId); res.setHeader("X-Request-Id",requestId);
                        String header=req.getHeader("Authorization");
                        if (secret.isEmpty() || header==null || !MessageDigest.isEqual(
                                ("Bearer "+secret).getBytes(StandardCharsets.UTF_8),header.getBytes(StandardCharsets.UTF_8))) {
                            error(json,req,res,401); return;
                        }
                        SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken("analytics-reader",null,List.of(new SimpleGrantedAuthority("events:read"))));
                        chain.doFilter(req,res);
                    }
                },UsernamePasswordAuthenticationFilter.class).build();
    }
    private static void error(ObjectMapper json,HttpServletRequest req,HttpServletResponse res,int status) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8");
        json.writeValue(res.getWriter(),ApiErrors.body(status,status==401 ? "UNAUTHORIZED" : "FORBIDDEN",
                "分析服务访问被拒绝",req.getAttribute("requestId")));
    }
}
