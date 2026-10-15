package com.pixelflow.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixelflow.server.common.ApiErrors;
import com.pixelflow.server.common.ApiException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, AuthService auth, ObjectMapper json) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable())
                .authorizeHttpRequests(r -> r
                        .requestMatchers(HttpMethod.POST,"/api/auth/register","/api/auth/login","/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET,"/health","/feed/","/api/posts/*","/demo-media/**","/demo-assets/**","/api/media/*/content").permitAll()
                        .requestMatchers(HttpMethod.HEAD,"/demo-assets/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req,res,error) -> write(json,req,res,401,"UNAUTHORIZED"))
                        .accessDeniedHandler((req,res,error) -> write(json,req,res,403,"FORBIDDEN")))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
                            throws ServletException,IOException {
                        String requestId = UUID.randomUUID().toString();
                        req.setAttribute("requestId",requestId); res.setHeader("X-Request-Id",requestId);
                        String header = req.getHeader("Authorization");
                        if (header != null) {
                            if (!header.startsWith("Bearer ")) { write(json,req,res,401,"UNAUTHORIZED"); return; }
                            try {
                                var actor = auth.authenticate(header.substring(7));
                                var authentication = new UsernamePasswordAuthenticationToken(actor,null,
                                        List.of(new SimpleGrantedAuthority("ROLE_" + actor.role())));
                                SecurityContextHolder.getContext().setAuthentication(authentication);
                            } catch (JwtException | ApiException | IllegalArgumentException error) {
                                write(json,req,res,401,"UNAUTHORIZED"); return;
                            }
                        }
                        chain.doFilter(req,res);
                    }
                },UsernamePasswordAuthenticationFilter.class).build();
    }
    private static void write(ObjectMapper json,HttpServletRequest req,HttpServletResponse res,int status,String code) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8");
        json.writeValue(res.getWriter(),ApiErrors.body(status,code,status==401 ? "请先登录" : "无权执行此操作",req.getAttribute("requestId")));
    }
}
