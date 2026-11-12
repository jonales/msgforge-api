package dev.msgforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ApiKeyAttemptLimiterTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void blocksAfterTheConfiguredFailures() {
        var limiter = new ApiKeyAttemptLimiter(3, clock);
        for (var i = 0; i < 3; i++) {
            assertThat(limiter.blocked("1.2.3.4")).isFalse();
            limiter.failed("1.2.3.4");
        }

        assertThat(limiter.blocked("1.2.3.4")).isTrue();
        assertThat(limiter.blocked("5.6.7.8")).isFalse();
        assertThat(limiter.retryAfterSeconds("1.2.3.4")).isEqualTo(15 * 60);
    }

    @Test
    void zeroDisablesTheLimit() {
        var limiter = new ApiKeyAttemptLimiter(0, clock);
        for (var i = 0; i < 1000; i++) {
            limiter.failed("1.2.3.4");
        }

        assertThat(limiter.blocked("1.2.3.4")).isFalse();
    }
}
