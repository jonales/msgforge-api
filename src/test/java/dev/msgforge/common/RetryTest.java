// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryTest {

    @Test
    void stopsAtTheFirstSuccess() {
        var calls = new AtomicInteger();
        var failures = new ArrayList<Integer>();
        var ok = Retry.run(3, Duration.ofMillis(1), () -> {
            if (calls.incrementAndGet() < 2) {
                throw new IllegalStateException("conexão quebrada");
            }
        }, (attempt, e) -> failures.add(attempt));
        assertThat(ok).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(failures).containsExactly(1);
    }

    @Test
    void givesUpAfterTheLastAttemptWithoutThrowing() {
        var calls = new AtomicInteger();
        var failures = new ArrayList<Integer>();
        var ok = Retry.run(3, Duration.ofMillis(1), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("banco fora");
        }, (attempt, e) -> failures.add(attempt));
        assertThat(ok).isFalse();
        assertThat(calls).hasValue(3);
        assertThat(failures).containsExactly(1, 2, 3);
    }

    @Test
    void needsAtLeastOneAttempt() {
        assertThatThrownBy(() -> Retry.run(0, Duration.ZERO, () -> { }, (a, e) -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
