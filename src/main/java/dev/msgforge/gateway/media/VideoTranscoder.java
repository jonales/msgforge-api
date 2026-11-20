// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.gateway.media;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.gateway.WhatsAppGateway.GatewayException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Converte para H.264 o vídeo MP4/MOV que vem em outro codec (o HEVC/H.265 do iPhone, por exemplo):
 * o ffmpeg embutido no motor não tem decodificador de HEVC, e a documentação do WhatsApp recomenda
 * H.264 "Main" sem B-frames. Vídeo que já é H.264 passa direto.
 *
 * <p>Diferente do {@link AudioTranscoder}, usa arquivos temporários: o MOV do iPhone costuma trazer o
 * índice ({@code moov}) no fim, e o ffmpeg precisa voltar no arquivo para lê-lo, o que um pipe não
 * permite. Os dois arquivos são apagados no fim, com ou sem erro.
 */
@Component
public class VideoTranscoder {

    private static final Logger log = LoggerFactory.getLogger(VideoTranscoder.class);
    /** Vídeo leva mais que áudio: o limite de conversão do áudio vezes isto. */
    static final int TIMEOUT_FACTOR = 5;

    private final MediaProperties properties;
    private final AudioTranscoder ffmpeg;

    public VideoTranscoder(MediaProperties properties, AudioTranscoder ffmpeg) {
        this.properties = properties;
        this.ffmpeg = ffmpeg;
    }

    /** O ffmpeg existe? (a mesma checagem do áudio) */
    public boolean available() {
        return ffmpeg.available();
    }

    /**
     * Precisa converter? Só MP4/MOV ({@code ftyp} no começo) cujo vídeo não é H.264: o codec é lido
     * pelo nome da amostra no {@code stsd} ({@code avc1}/{@code avc3} é H.264; {@code hvc1}/{@code hev1}
     * é HEVC). Sem nenhum dos nomes conhecidos, não mexe (o motor decide, como antes).
     */
    public static boolean needsH264(byte[] video) {
        if (video.length < 12 || video[4] != 'f' || video[5] != 't' || video[6] != 'y' || video[7] != 'p') {
            return false;
        }
        var text = new String(video, StandardCharsets.ISO_8859_1);
        if (text.contains("avc1") || text.contains("avc3")) {
            return false;
        }
        return text.contains("hvc1") || text.contains("hev1") || text.contains("vp09") || text.contains("av01");
    }

    /**
     * @throws BadRequestException se o ffmpeg não reconhecer o vídeo ou o resultado passar do limite
     * @throws GatewayException    se o ffmpeg falhar, estourar o tempo ou não existir
     */
    public byte[] toH264(byte[] input) {
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("msgforge-video-", ".in");
            out = Files.createTempFile("msgforge-video-", ".mp4");
            Files.write(in, input);
            var process = new ProcessBuilder(command(properties.ffmpegPath(), in, out))
                    .redirectErrorStream(true).start();
            // A saída é lida à parte: ler aqui travaria até o fim do processo, e o limite de tempo não valeria
            var outputLog = new java.util.concurrent.CompletableFuture<byte[]>();
            Thread.ofVirtual().start(() -> {
                try (var stream = process.getInputStream()) {
                    var head = stream.readNBytes(4096);
                    stream.transferTo(java.io.OutputStream.nullOutputStream());
                    outputLog.complete(head);
                } catch (IOException e) {
                    outputLog.complete(new byte[0]);
                }
            });
            var timeout = properties.transcodeTimeoutMs() * TIMEOUT_FACTOR;
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GatewayException("Conversão de vídeo passou de " + timeout + " ms", null);
            }
            if (process.exitValue() != 0) {
                var error = new String(outputLog.getNow(new byte[0]), StandardCharsets.UTF_8).strip();
                throw new BadRequestException("Could not convert video: "
                        + (error.isEmpty() ? "ffmpeg exit " + process.exitValue() : error.lines().findFirst().orElse(error)));
            }
            if (Files.size(out) > properties.maxOutputBytes()) {
                throw new BadRequestException("Converted video is larger than " + properties.maxOutputBytes() + " bytes");
            }
            log.info("Vídeo convertido para H.264 ({} → {} bytes)", input.length, Files.size(out));
            return Files.readAllBytes(out);
        } catch (IOException e) {
            throw new GatewayException("Conversão de vídeo falhou: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Conversão de vídeo interrompida", e);
        } finally {
            delete(in);
            delete(out);
        }
    }

    /**
     * {@code -f mov} fixa o demuxer (MP4/MOV) e {@code -protocol_whitelist file} impede que o arquivo,
     * que vem de quem chamou a API, faça o ffmpeg abrir outra coisa. Dimensões pares (o yuv420p exige),
     * áudio AAC se houver, sem metadados e com o índice no começo ({@code +faststart}).
     */
    static List<String> command(String ffmpeg, Path in, Path out) {
        return List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-protocol_whitelist", "file", "-f", "mov", "-i", in.toString(),
                "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "-1",
                "-c:v", "libx264", "-profile:v", "main", "-bf", "0", "-pix_fmt", "yuv420p",
                "-preset", "veryfast", "-crf", "23", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-f", "mp4", out.toString());
    }

    private static void delete(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Não apagou o arquivo temporário de vídeo {}", path.getFileName());
            }
        }
    }
}
