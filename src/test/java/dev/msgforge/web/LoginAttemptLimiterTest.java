package dev.msgforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    /** Relógio que o teste avança na mão. */
    static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-11-11T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final ManualClock clock = new ManualClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    void blocksAfterTooManyFailuresAndReleasesWhenTheWindowPasses() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES - 1; i++) {
            limiter.failed("10.0.0.1");
        }
        assertThat(limiter.blocked("10.0.0.1")).isFalse();
        limiter.failed("10.0.0.1");
        assertThat(limiter.blocked("10.0.0.1")).isTrue();
        assertThat(limiter.blocked("10.0.0.2")).as("outro IP não é afetado").isFalse();

        clock.now = clock.now.plus(LoginAttemptLimiter.WINDOW).plus(Duration.ofSeconds(1));
        assertThat(limiter.blocked("10.0.0.1")).as("falhas antigas saem da janela").isFalse();
    }

    @Test
    void successClearsTheFailures() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES - 1; i++) {
            limiter.failed("10.0.0.1");
        }
        limiter.succeeded("10.0.0.1");
        limiter.failed("10.0.0.1");
        assertThat(limiter.blocked("10.0.0.1")).isFalse();
    }
}
