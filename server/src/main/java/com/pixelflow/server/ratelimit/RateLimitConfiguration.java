package com.pixelflow.server.ratelimit;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {
    @Bean ClientIpResolver clientIpResolver(RateLimitProperties properties) {
        return new ClientIpResolver(properties.trustedProxies());
    }
    @Bean RedisRateLimiter redisRateLimiter(RateLimitProperties properties,StringRedisTemplate redis) {
        return new RedisRateLimiter(properties,redis);
    }
    @Bean LettuceClientConfigurationBuilderCustomizer rateLimitRedisOptions() {
        return builder -> builder.clientOptions(builder.build().getClientOptions()
                .orElseGet(() -> ClientOptions.builder().build()).mutate()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true).requestQueueSize(32).build());
    }
}
