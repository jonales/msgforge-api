// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.gateway.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Converte de verdade com o ffmpeg do sistema; sem ffmpeg (ou sem libx265 para gerar o HEVC), pula. */
class VideoTranscoderTest {

    private static final MediaProperties PROPERTIES = new MediaProperties("ffmpeg", 30_000, 1 << 24);
    private final VideoTranscoder videos = new VideoTranscoder(PROPERTIES, new AudioTranscoder(PROPERTIES));

    private static byte[] mp4(String brandAndCodec) {
        var bytes = new byte[64];
        var text = ("\0\0\0 ftypisom" + brandAndCodec).getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(text, 0, bytes, 0, Math.min(text.length, bytes.length));
        return bytes;
    }

    @Test
    void onlyNonH264Mp4IsConverted() {
        assertThat(VideoTranscoder.needsH264(mp4("....stsd....hvc1"))).isTrue();
        assertThat(VideoTranscoder.needsH264(mp4("....stsd....hev1"))).isTrue();
        assertThat(VideoTranscoder.needsH264(mp4("....stsd....avc1"))).isFalse();
        assertThat(VideoTranscoder.needsH264(mp4("....stsd....mp4v"))).isFalse();   // desconhecido: não mexe
        assertThat(VideoTranscoder.needsH264(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})).isFalse();   // WebM
    }

    @Test
    void theDemuxerAndProtocolsAreFixed() {
        var command = VideoTranscoder.command("ffmpeg", Path.of("/tmp/in"), Path.of("/tmp/out.mp4"));
        assertThat(String.join(" ", command))
                .contains("-protocol_whitelist file -f mov -i /tmp/in")
                .contains("-c:v libx264 -profile:v main -bf 0 -pix_fmt yuv420p")
                .contains("-movflags +faststart");
    }

    @Test
    void convertsHevcToH264(@TempDir Path dir) throws Exception {
        assumeTrue(videos.available(), "ffmpeg não instalado");
        var hevc = dir.resolve("hevc.mp4");
        var made = new ProcessBuilder(List.of("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi",
                "-i", "testsrc=size=320x240:rate=15:duration=1", "-c:v", "libx265", "-tag:v", "hvc1", hevc.toString()))
                .redirectErrorStream(true).start();
        made.getInputStream().readAllBytes();
        assumeTrue(made.waitFor() == 0, "ffmpeg sem libx265 para gerar o vídeo de teste");
        var input = Files.readAllBytes(hevc);
        assertThat(VideoTranscoder.needsH264(input)).isTrue();

        var output = videos.toH264(input);

        assertThat(VideoTranscoder.needsH264(output)).isFalse();
        assertThat(new String(output, StandardCharsets.ISO_8859_1)).contains("avc1");
    }

    @Test
    void garbageIsABadRequest() {
        assumeTrue(videos.available(), "ffmpeg não instalado");
        assertThatThrownBy(() -> videos.toH264(mp4("....hvc1 isto não é vídeo")))
                .isInstanceOf(BadRequestException.class);
    }
}
