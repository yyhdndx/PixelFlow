package com.pixelflow.server.ratelimit;

import java.time.Duration;
import java.util.List;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RateLimitConfigurationTest {
    @Test void invalidConfigurationFailsDuringContextStartup() {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(RateLimitConfiguration.class)
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class))
                .withPropertyValues("pixelflow.rate-limit.namespace=test","pixelflow.rate-limit.login-ip.limit=0",
                        "pixelflow.rate-limit.login-ip.window=1s","pixelflow.rate-limit.login-account.limit=5",
                        "pixelflow.rate-limit.login-account.window=1s","pixelflow.rate-limit.register-ip.limit=5",
                        "pixelflow.rate-limit.register-ip.window=1s","pixelflow.rate-limit.upload-user.limit=5",
                        "pixelflow.rate-limit.upload-user.window=1s");
        runner.run(context -> {
            assertNotNull(context.getStartupFailure());
            Throwable cause=context.getStartupFailure();
            while (cause.getCause()!=null) cause=cause.getCause();
            assertTrue(cause.getMessage().contains("limit"));
        });
    }

    @Test void invalidLimitsAndWindowsAreRejected() {
        try (var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            var valid=new RateLimitProperties.Rule(5,Duration.ofSeconds(1));
            assertTrue(validator.validate(valid).isEmpty());
            assertFalse(validator.validate(new RateLimitProperties.Rule(0,Duration.ofSeconds(1))).isEmpty());
            assertFalse(validator.validate(new RateLimitProperties.Rule(5,Duration.ZERO)).isEmpty());
            assertFalse(validator.validate(new RateLimitProperties.Rule(5,Duration.ofNanos(1))).isEmpty());
            assertFalse(validator.validate(new RateLimitProperties.Rule(5,Duration.ofDays(366))).isEmpty());
            assertFalse(validator.validate(new RateLimitProperties(false,"",List.of(),valid,valid,valid,valid)).isEmpty());
        }
    }
}
