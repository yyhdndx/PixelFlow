package com.pixelflow.server.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("pixelflow.rate-limit")
public record RateLimitProperties(boolean enabled,
        @NotBlank @Pattern(regexp="[A-Za-z0-9:_-]{1,100}") String namespace,
        List<String> trustedProxies, @Valid @NotNull Rule loginIp,
        @Valid @NotNull Rule loginAccount, @Valid @NotNull Rule registerIp,
        @Valid @NotNull Rule uploadUser) {
    public record Rule(@Min(1) long limit, @NotNull Duration window) {
        @AssertTrue(message="window must be between 1 ms and 365 days")
        public boolean isWindowValid() {
            return window != null && window.compareTo(Duration.ofMillis(1)) >= 0
                    && window.compareTo(Duration.ofDays(365)) <= 0;
        }
    }
}
