package dev.msgforge.gateway.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.gateway.WhatsAppGateway.GatewayException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** Converte de verdade com o ffmpeg do sistema; sem ffmpeg, os testes de conversão são pulados. */
class AudioTranscoderTest {

    private final AudioTranscoder transcoder = new AudioTranscoder(new MediaProperties("ffmpeg", 30_000, 1 << 24));

    @Test
    void convertsWavToOggOpus() {
        assumeTrue(transcoder.available(), "ffmpeg não instalado");

        var wav = wav(8000, 1.0);
        assertThat(AudioTranscoder.isOggOpus(wav)).isFalse();

        var ogg = transcoder.toOggOpus(wav);

        assertThat(AudioTranscoder.isOggOpus(ogg)).isTrue();
        assertThat(ogg.length).isLessThan(wav.length);
    }

    @Test
    void rejectsBytesThatAreNotAudio() {
        assumeTrue(transcoder.available(), "ffmpeg não instalado");

        assertThatThrownBy(() -> transcoder.toOggOpus("isto não é áudio".repeat(20).getBytes()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("Could not convert audio");
    }

    @Test
    void outputLargerThanLimitIsRejected() {
        assumeTrue(transcoder.available(), "ffmpeg não instalado");
        var tiny = new AudioTranscoder(new MediaProperties("ffmpeg", 30_000, 64));

        assertThatThrownBy(() -> tiny.toOggOpus(wav(8000, 1.0)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("larger than 64");
    }

    @Test
    void missingBinaryIsReportedOnceAndConversionFailsAsGatewayError() {
        var missing = new AudioTranscoder(new MediaProperties("/nao/existe/ffmpeg", 1000, 1024));

        assertThat(missing.available()).isFalse();
        assertThat(missing.available()).isFalse();
        assertThatThrownBy(() -> missing.toOggOpus(new byte[100])).isInstanceOf(GatewayException.class);
    }

    @Test
    void recognizesOggOpusHeader() {
        var header = new byte[64];
        System.arraycopy("OggS".getBytes(), 0, header, 0, 4);
        System.arraycopy("OpusHead".getBytes(), 0, header, 28, 8);
        assertThat(AudioTranscoder.isOggOpus(header)).isTrue();

        var vorbis = header.clone();
        System.arraycopy("vorbis!!".getBytes(), 0, vorbis, 28, 8);
        assertThat(AudioTranscoder.isOggOpus(vorbis)).isFalse();
        assertThat(AudioTranscoder.isOggOpus(new byte[3])).isFalse();
    }

    /** WAV PCM 16 bits mono com um seno de 440 Hz. */
    static byte[] wav(int sampleRate, double seconds) {
        var samples = (int) (sampleRate * seconds);
        var data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < samples; i++) {
            data.putShort((short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 12_000));
        }
        var header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + samples * 2).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(samples * 2);
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        out.writeBytes(data.array());
        return out.toByteArray();
    }
}
