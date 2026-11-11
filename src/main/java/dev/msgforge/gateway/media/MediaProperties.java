package dev.msgforge.gateway.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Processamento de mídia com o ffmpeg.
 *
 * @param ffmpegPath         binário do ffmpeg (FFMPEG_PATH); a imagem Docker já traz o ffmpeg
 * @param transcodeTimeoutMs limite de uma conversão de áudio
 * @param maxOutputBytes     tamanho máximo do áudio convertido
 */
@ConfigurationProperties(prefix = "msgforge.media")
public record MediaProperties(
        @DefaultValue("ffmpeg") String ffmpegPath,
        @DefaultValue("60000") long transcodeTimeoutMs,
        @DefaultValue("33554432") long maxOutputBytes) {}
