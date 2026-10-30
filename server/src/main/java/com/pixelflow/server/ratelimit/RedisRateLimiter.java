package com.pixelflow.server.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisRateLimiter {
    private final RateLimitProperties properties;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script=new DefaultRedisScript<>();
    private final AtomicLong failures=new AtomicLong();
    private final AtomicLong nextLog=new AtomicLong();

    public RedisRateLimiter(RateLimitProperties properties, StringRedisTemplate redis) {
        this.properties=properties; this.redis=redis;
        script.setLocation(new ClassPathResource("ratelimit/fixed-window.lua"));
        script.setResultType(List.class);
    }

    public record Attempt(String rule, RateLimitProperties.Rule quota, List<String> identity) {}

    public long retryAfter(List<Attempt> attempts) {
        long result=count(attempts);
        if (properties.enabled() && !attempts.isEmpty()) recordOutcome(result<0);
        return result;
    }

    long count(List<Attempt> attempts) {
        if (!properties.enabled() || attempts.isEmpty()) return 0;
        long remaining=0;
        boolean failed=false;
        for (Attempt attempt:attempts) {
            try {
                var result=redis.execute(script,List.of(key(attempt)),Long.toString(attempt.quota().window().toMillis()));
                if (result==null || result.size()!=2) throw new IllegalStateException("Invalid limiter script response");
                long count=((Number)result.get(0)).longValue(),ttl=((Number)result.get(1)).longValue();
                if (count>attempt.quota().limit()) remaining=Math.max(remaining,Math.max(1,(ttl+999)/1000));
            } catch (RuntimeException error) {
                failed=true; failures.incrementAndGet();
            }
        }
        return failed ? -1 : remaining;
    }

    private boolean degraded;

    synchronized void recordOutcome(boolean failed) {
        if (failed) {
            degraded=true;
            long now=System.nanoTime(),previous=nextLog.get();
            if (previous==0 || now-previous>=0) {
                nextLog.set(now+60_000_000_000L);
                LoggerFactory.getLogger(RedisRateLimiter.class).warn("Rate limiting degraded: Redis unavailable; fail-open; failures={}",failures.get());
            }
        } else if (degraded) {
            degraded=false;
            LoggerFactory.getLogger(RedisRateLimiter.class).info("Rate limiting recovered; Redis commands available; failures={}",failures.get());
        }
    }

    public long failureCount() { return failures.get(); }

    String key(Attempt attempt) {
        StringBuilder identity=new StringBuilder();
        for (String value:attempt.identity()) identity.append(value.length()).append(':').append(value);
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8));
            return properties.namespace()+":v1:"+attempt.rule()+":"+HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
