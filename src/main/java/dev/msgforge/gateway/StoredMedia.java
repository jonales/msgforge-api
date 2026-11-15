// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.gateway;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * O que o histórico guarda de uma mídia (os campos do {@code messages.upsert}: {@code url},
 * {@code directPath}, {@code mediaKey}, hashes...), para o motor baixar uma mensagem que ele não tem
 * mais na memória. Tipo neutro: cada motor monta a própria mensagem a partir dele.
 *
 * <p>{@code messageType} é o do contrato ({@code imageMessage}, {@code audioMessage}...). Os bytes
 * chegam em base64 no JSON; horários em segundos (epoch).
 */
public record StoredMedia(String messageType, String url, String directPath, String mimetype, byte[] mediaKey,
                          byte[] fileSha256, byte[] fileEncSha256, Long fileLength, Long mediaKeyTimestamp,
                          boolean ptt) {

    /** Os tipos com mídia baixável. */
    public static final Set<String> TYPES = Set.of("imageMessage", "videoMessage", "ptvMessage", "audioMessage",
            "documentMessage", "stickerMessage");

    /**
     * Lê os campos gravados. Vazio quando o tipo não tem mídia ou falta o essencial para baixar
     * (a chave e um endereço): mensagens antigas, gravadas antes destes campos, ficam sem mídia.
     */
    public static Optional<StoredMedia> of(String messageType, Map<?, ?> fields) {
        if (messageType == null || fields == null || !TYPES.contains(messageType)) {
            return Optional.empty();
        }
        var key = bytes(fields.get("mediaKey"));
        var url = text(fields.get("url"));
        var directPath = text(fields.get("directPath"));
        if (key == null || (url == null && directPath == null)) {
            return Optional.empty();
        }
        return Optional.of(new StoredMedia(messageType, url, directPath, text(fields.get("mimetype")), key,
                bytes(fields.get("fileSha256")), bytes(fields.get("fileEncSha256")), number(fields.get("fileLength")),
                number(fields.get("mediaKeyTimestamp")), Boolean.TRUE.equals(fields.get("ptt"))));
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static byte[] bytes(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Número do JSON, ou texto com dígitos (o int64 do protobuf costuma sair como string). */
    private static Long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && s.matches("\\d{1,18}")) {
            return Long.parseLong(s);
        }
        return null;
    }
}
