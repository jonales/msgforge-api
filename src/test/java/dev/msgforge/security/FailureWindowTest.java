package dev.msgforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class FailureWindowTest {

    static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-11-12T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final ManualClock clock = new ManualClock();

    @Test
    void retryAfterCountsFromTheOldestFailure() {
        var window = new FailureWindow(2, Duration.ofMinutes(15), clock);
        window.failed("1.2.3.4");
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        window.failed("1.2.3.4");

        assertThat(window.blocked("1.2.3.4")).isTrue();
        assertThat(window.retryAfter("1.2.3.4")).isEqualTo(Duration.ofMinutes(10));
        assertThat(window.retryAfter("5.6.7.8")).isZero();
    }

    @Test
    void manyAddressesDoNotGrowWithoutBound() {
        var window = new FailureWindow(5, Duration.ofMinutes(15), clock);
        for (var i = 0; i < FailureWindow.CLEANUP_THRESHOLD; i++) {
            window.failed("10.0." + (i / 256) + "." + (i % 256));
        }
        clock.now = clock.now.plus(Duration.ofMinutes(16));   // todas saíram da janela

        window.failed("192.168.0.1");

        assertThat(window.trackedKeys()).isLessThanOrEqualTo(2);
    }
}
