package dev.msgforge.gateway.media;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.gateway.WhatsAppGateway.GatewayException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Converte áudio para OGG/Opus mono 48 kHz, o formato das mensagens de voz do WhatsApp, chamando
 * o ffmpeg como processo externo. Entrada pelo stdin e saída pelo stdout (nada em disco); stdout
 * e stderr são lidos em paralelo para o processo nunca travar com o buffer cheio.
 */
@Component
public class AudioTranscoder {

    private static final Logger log = LoggerFactory.getLogger(AudioTranscoder.class);
    private static final int STDERR_LIMIT = 4096;

    private final MediaProperties properties;
    private volatile Boolean available;

    public AudioTranscoder(MediaProperties properties) {
        this.properties = properties;
    }

    /** O ffmpeg existe e responde? (verificado uma vez) */
    public boolean available() {
        var known = available;
        if (known == null) {
            known = probe();
            available = known;
            if (!known) {
                log.warn("ffmpeg não encontrado em '{}': áudios de voz serão enviados sem conversão",
                        properties.ffmpegPath());
            }
        }
        return known;
    }

    /** Já é OGG com Opus? Então não precisa converter. */
    public static boolean isOggOpus(byte[] audio) {
        if (audio.length < 36 || audio[0] != 'O' || audio[1] != 'g' || audio[2] != 'g' || audio[3] != 'S') {
            return false;
        }
        var head = new String(audio, 0, Math.min(audio.length, 128), StandardCharsets.ISO_8859_1);
        return head.contains("OpusHead");
    }

    /**
     * @throws BadRequestException se o ffmpeg não reconhecer o áudio
     * @throws GatewayException    se o ffmpeg falhar, estourar o tempo ou não existir
     */
    public byte[] toOggOpus(byte[] input) {
        var command = List.of(properties.ffmpegPath(), "-hide_banner", "-loglevel", "error", "-nostdin",
                "-i", "pipe:0", "-vn", "-map_metadata", "-1",
                "-ac", "1", "-ar", "48000", "-c:a", "libopus", "-b:a", "32k", "-application", "voip",
                "-f", "ogg", "pipe:1");
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new GatewayException("ffmpeg indisponível: " + e.getMessage(), e);
        }
        var stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream(), properties.maxOutputBytes()),
                runnable -> Thread.ofVirtual().start(runnable));
        var stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream(), STDERR_LIMIT),
                runnable -> Thread.ofVirtual().start(runnable));
        Thread.ofVirtual().start(() -> {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input);
            } catch (IOException ignored) {
                // o ffmpeg pode fechar o stdin cedo (entrada inválida); o código de saída conta a história
            }
        });
        try {
            if (!process.waitFor(properties.transcodeTimeoutMs(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GatewayException("Conversão de áudio passou de " + properties.transcodeTimeoutMs() + " ms", null);
            }
            var output = stdout.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                var error = new String(stderr.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
                throw new BadRequestException("Could not convert audio: "
                        + (error.isEmpty() ? "ffmpeg exit " + process.exitValue() : error.lines().findFirst().orElse(error)));
            }
            if (output.length > properties.maxOutputBytes()) {
                throw new BadRequestException("Converted audio is larger than " + properties.maxOutputBytes() + " bytes");
            }
            return output;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GatewayException("Conversão de áudio interrompida", e);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            process.destroyForcibly();
            throw new GatewayException("Falha lendo a saída do ffmpeg", e);
        }
    }

    /** Lê até {@code limit + 1} bytes (o byte extra indica que passou do limite) e descarta o resto. */
    private static byte[] read(InputStream in, long limit) {
        try (in) {
            var out = new ByteArrayOutputStream();
            var buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() <= limit) {
                    out.write(buffer, 0, (int) Math.min(n, limit + 1 - out.size()));
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private boolean probe() {
        try {
            var process = new ProcessBuilder(properties.ffmpegPath(), "-hide_banner", "-version")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
