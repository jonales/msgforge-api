package dev.msgforge.gateway.media;

/** Expõe o WAV de teste para outros pacotes. */
public final class AudioTranscoderTestSupport {

    private AudioTranscoderTestSupport() {}

    public static byte[] wav() {
        return AudioTranscoderTest.wav(8000, 1.0);
    }
}
