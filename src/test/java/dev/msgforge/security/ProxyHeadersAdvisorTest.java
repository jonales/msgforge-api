// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProxyHeadersAdvisorTest {

    @ParameterizedTest
    @ValueSource(strings = {"172.18.0.1", "10.0.0.5", "192.168.1.10", "fd00::1"})
    void privateAddressesLookLikeAProxy(String ip) {
        assertThat(ProxyHeadersAdvisor.privateNonLoopback(ip)).as(ip).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "8.8.8.8", "2001:4860:4860::8888", "nao-e-ip"})
    void loopbackPublicAndGarbageDoNot(String ip) {
        assertThat(ProxyHeadersAdvisor.privateNonLoopback(ip)).as(ip).isFalse();
    }

    @Test
    void neverFailsWhateverTheAddress() {
        var advisor = new ProxyHeadersAdvisor("");
        advisor.seen("172.18.0.1");
        advisor.seen("172.18.0.1");
        advisor.seen(null);
        advisor.blocked("172.18.0.1");
        advisor.blocked("8.8.8.8");
        new ProxyHeadersAdvisor("172.18.0.0/16").seen("172.18.0.1");
    }
}
