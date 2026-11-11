package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.msgforge.gateway.cobalt.CobaltSessionCodec.Parts;
import org.junit.jupiter.api.Test;

class CobaltSessionCodecTest {

    @Test
    void roundTripsBothPartsAndMissingParts() {
        var both = CobaltSessionCodec.decode(CobaltSessionCodec.encode(new Parts(new byte[] {1, 2}, new byte[] {3})));
        assertThat(both.keys()).containsExactly(1, 2);
        assertThat(both.store()).containsExactly(3);

        var onlyKeys = CobaltSessionCodec.decode(CobaltSessionCodec.encode(Parts.EMPTY.withKeys(new byte[0])));
        assertThat(onlyKeys.keys()).isEmpty();
        assertThat(onlyKeys.store()).isNull();

        assertThat(CobaltSessionCodec.decode(null)).isEqualTo(Parts.EMPTY);
    }

    @Test
    void rejectsForeignOrTruncatedBlobs() {
        assertThatThrownBy(() -> CobaltSessionCodec.decode("credenciais".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        var valid = CobaltSessionCodec.encode(new Parts(new byte[] {1, 2, 3}, null));
        var truncated = java.util.Arrays.copyOf(valid, valid.length - 1);
        assertThatThrownBy(() -> CobaltSessionCodec.decode(truncated)).isInstanceOf(IllegalArgumentException.class);
    }
}
