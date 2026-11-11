package dev.msgforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrivateNetworkGuardTest {

    private final PrivateNetworkGuard enabled = new PrivateNetworkGuard(true);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/h", "http://localhost:8080/h", "http://10.0.0.5/h", "http://172.16.3.4/h",
            "http://192.168.0.10/h", "http://169.254.169.254/latest/meta-data", "http://0.0.0.0/h",
            "http://100.64.1.1/h", "http://[::1]/h", "http://[fd00::1]/h", "http://[fe80::1]/h",
            "http://[::ffff:127.0.0.1]/h", "http://224.0.0.1/h"})
    void blocksNonPublicAddresses(String url) {
        assertThat(enabled.check(url)).as(url).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://8.8.8.8/h", "https://1.1.1.1/h", "http://[2001:4860:4860::8888]/h"})
    void allowsPublicAddresses(String url) {
        assertThat(enabled.check(url)).as(url).isEmpty();
    }

    @Test
    void unresolvableHostsAreLeftToFailOnConnect() {
        assertThat(enabled.check("http://nao-existe.invalid/h")).isEmpty();
    }

    @Test
    void disabledGuardAllowsEverything() {
        assertThat(new PrivateNetworkGuard(false).check("http://127.0.0.1/h")).isEmpty();
    }
}
