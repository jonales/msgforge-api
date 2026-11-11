package dev.msgforge.gateway.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class MediaSignaturesTest {

    @Test
    void acceptsMp4AndWebm() {
        var mp4 = HexFormat.of().parseHex("000000206674797069736f6d00000200");
        var webm = HexFormat.of().parseHex("1a45dfa3010000000000001f4286");

        assertThat(MediaSignatures.isVideo(mp4)).isTrue();
        assertThat(MediaSignatures.isVideo(webm)).isTrue();
    }

    @Test
    void rejectsAnythingElse() {
        assertThat(MediaSignatures.isVideo("não é vídeo nenhum".getBytes())).isFalse();
        assertThat(MediaSignatures.isVideo(HexFormat.of().parseHex("89504e470d0a1a0a0000000d"))).isFalse(); // PNG
        assertThat(MediaSignatures.isVideo(new byte[5])).isFalse();
        assertThat(MediaSignatures.isVideo(null)).isFalse();
    }
}
