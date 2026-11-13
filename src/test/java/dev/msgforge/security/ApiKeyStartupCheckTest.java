package dev.msgforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.msgforge.config.MsgforgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ApiKeyStartupCheckTest {

    private static final String STRONG = "a".repeat(ApiKeyStartupCheck.MIN_LENGTH);

    @Test
    void defaultEmptyOrShortKeysAreRejectedOutsideDev() {
        for (var key : new String[] {null, "", "   ", "troque-esta-chave", "a".repeat(31)}) {
            assertThatThrownBy(() -> check(key))
                    .as("chave %s", key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUTHENTICATION_API_KEY")
                    .hasMessageContaining("SPRING_PROFILES_ACTIVE=dev");
        }
    }

    @Test
    void strongKeyStarts() {
        assertThatCode(() -> check(STRONG)).doesNotThrowAnyException();
        assertThat(ApiKeyStartupCheck.problem(STRONG)).isEmpty();
    }

    @Test
    void devProfileAcceptsTheDefaultKey() {
        var env = new MockEnvironment();
        env.setActiveProfiles("dev");
        assertThatCode(() -> new ApiKeyStartupCheck(properties("troque-esta-chave"), env))
                .doesNotThrowAnyException();
    }

    private static void check(String key) {
        new ApiKeyStartupCheck(properties(key), new MockEnvironment());
    }

    private static MsgforgeProperties properties(String key) {
        return new MsgforgeProperties(null, new MsgforgeProperties.Authentication(key, 30), null, null, "stub",
                null);
    }
}
