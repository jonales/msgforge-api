// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * O que uma mensagem do histórico é, lido do JSON gravado (formato Baileys: {@code message} com uma
 * chave por tipo, como o {@code CobaltMessageMapper} e os envios da API gravam). Cada tipo tem um
 * jeito próprio de aparecer na conversa; o que não é reconhecido vira {@link Unknown}, com um rótulo
 * neutro, nunca uma bolha vazia.
 *
 * <p>{@link #kind()} escolhe o trecho do template e o rótulo em {@code manager.messages.kind.*}.
 * Os records são públicos: o SpEL do Thymeleaf não chama métodos de records não públicos.
 */
public sealed interface MessageContent {

    /** {@code text}, {@code image}, {@code video}, {@code audio}, {@code document}, {@code sticker},
     * {@code location}, {@code contact}, {@code reaction}, {@code poll}, {@code revoked} ou {@code unknown}. */
    String kind();

    /** O texto que a pessoa escreveu (texto, legenda, nome, emoji), para a lista de conversas; null sem texto. */
    String summary();

    /** O rótulo do tipo, em {@code manager.messages.kind.*}: nota de voz e GIF têm o seu. */
    default String labelKey() {
        return kind();
    }

    /** Há mídia para baixar (imagem, vídeo, áudio, documento, figurinha). */
    default boolean media() {
        return false;
    }

    record Text(String text) implements MessageContent {
        public String kind() { return "text"; }
        public String summary() { return text; }
    }

    record Image(String caption, String mimetype, Long size) implements MessageContent {
        public String kind() { return "image"; }
        public String summary() { return caption; }
        public boolean media() { return true; }
    }

    record Video(String caption, Integer seconds, boolean gif, Long size) implements MessageContent {
        public String kind() { return "video"; }
        public String summary() { return caption; }
        public boolean media() { return true; }
        public String duration() { return MessageContent.duration(seconds); }
        public String labelKey() { return gif ? "gif" : "video"; }
    }

    /** {@code voice}: nota de voz (ptt), gravada no próprio WhatsApp. */
    record Audio(Integer seconds, boolean voice, Long size) implements MessageContent {
        public String kind() { return "audio"; }
        public String labelKey() { return voice ? "voice" : "audio"; }
        public String summary() { return null; }
        public boolean media() { return true; }
        public String duration() { return MessageContent.duration(seconds); }
    }

    record Document(String fileName, String caption, String mimetype, Long size) implements MessageContent {
        public String kind() { return "document"; }
        public String summary() { return fileName != null ? fileName : caption; }
        public boolean media() { return true; }
        public String sizeLabel() { return MessageContent.size(size); }
    }

    record Sticker(boolean animated) implements MessageContent {
        public String kind() { return "sticker"; }
        public String summary() { return null; }
        public boolean media() { return true; }
    }

    record Location(Double latitude, Double longitude, String name, String address) implements MessageContent {
        public String kind() { return "location"; }
        public String summary() { return name; }

        /** "-15.7801, -47.9292", ou null sem coordenadas. */
        public String coordinates() {
            return latitude == null || longitude == null ? null
                    : String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude);
        }

        /** Link para o mapa (abre fora do Manager); null sem coordenadas. */
        public String mapUrl() {
            return latitude == null || longitude == null ? null
                    : String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f",
                            latitude, longitude, latitude, longitude);
        }
    }

    record Contact(List<String> names) implements MessageContent {
        public String kind() { return "contact"; }
        public String summary() { return names.isEmpty() ? null : String.join(", ", names); }
    }

    /** {@code emoji} vazio: a reação foi retirada. {@code targetId}: a mensagem reagida. */
    record Reaction(String emoji, String targetId) implements MessageContent {
        public String kind() { return "reaction"; }
        public String summary() { return emoji == null || emoji.isEmpty() ? null : emoji; }
        public boolean removed() { return emoji == null || emoji.isEmpty(); }
    }

    /** {@code multiple}: aceita mais de uma opção. */
    record Poll(String name, List<String> options, boolean multiple) implements MessageContent {
        public String kind() { return "poll"; }
        public String summary() { return name; }
    }

    record Revoked() implements MessageContent {
        public String kind() { return "revoked"; }
        public String summary() { return null; }
    }

    /** {@code type}: o tipo gravado, sem o sufixo "Message" ("buttonsResponse"), só para quem depura. */
    record Unknown(String type) implements MessageContent {
        public String kind() { return "unknown"; }
        public String summary() { return null; }
    }

    /** Embrulhos do WhatsApp com a mensagem de verdade dentro de {@code message}. */
    List<String> WRAPPERS = List.of("ephemeralMessage", "viewOnceMessage", "viewOnceMessageV2",
            "viewOnceMessageV2Extension", "documentWithCaptionMessage", "editedMessage");

    /** Lê a mensagem gravada; {@code type} é o {@code messageType} da linha (pode faltar). */
    static MessageContent of(String type, Map<String, Object> message) {
        if (message == null || message.isEmpty()) {
            return new Unknown(label(type));
        }
        var key = type != null && message.containsKey(type) ? type : message.keySet().iterator().next();
        return parse(key, message.get(key), 0);
    }

    /**
     * Os campos de mídia gravados (url, mediaKey, directPath...), já fora dos envelopes, para o motor
     * baixar a mídia sem a mensagem na memória dele. Vazio em mensagem antiga, sem esses campos.
     */
    static java.util.Optional<dev.msgforge.gateway.StoredMedia> stored(String type, Map<String, Object> message) {
        if (message == null || message.isEmpty()) {
            return java.util.Optional.empty();
        }
        var key = type != null && message.containsKey(type) ? type : message.keySet().iterator().next();
        Object content = message.get(key);
        for (var depth = 0; depth < 3 && WRAPPERS.contains(key) && content instanceof Map<?, ?> fields
                && fields.get("message") instanceof Map<?, ?> inner && !inner.isEmpty(); depth++) {
            key = String.valueOf(inner.keySet().iterator().next());
            content = inner.get(key);
        }
        return content instanceof Map<?, ?> fields
                ? dev.msgforge.gateway.StoredMedia.of(key, fields) : java.util.Optional.empty();
    }

    private static MessageContent parse(String type, Object content, int depth) {
        var fields = content instanceof Map<?, ?> map ? map : Map.of();
        if (WRAPPERS.contains(type) && depth < 3 && fields.get("message") instanceof Map<?, ?> inner && !inner.isEmpty()) {
            var innerType = String.valueOf(inner.keySet().iterator().next());
            return parse(innerType, inner.get(innerType), depth + 1);
        }
        return switch (type) {
            case "conversation" -> new Text(content instanceof String s ? s : text(fields, "text"));
            case "extendedTextMessage" -> new Text(content instanceof String s ? s : text(fields, "text"));
            case "imageMessage" -> new Image(text(fields, "caption"), text(fields, "mimetype"), number(fields, "fileLength"));
            case "videoMessage", "ptvMessage" -> new Video(text(fields, "caption"), integer(fields, "seconds"),
                    Boolean.TRUE.equals(fields.get("gifPlayback")), number(fields, "fileLength"));
            case "audioMessage" -> new Audio(integer(fields, "seconds"), Boolean.TRUE.equals(fields.get("ptt")),
                    number(fields, "fileLength"));
            case "documentMessage" -> new Document(first(fields, "fileName", "title"), text(fields, "caption"),
                    text(fields, "mimetype"), number(fields, "fileLength"));
            case "stickerMessage" -> new Sticker(Boolean.TRUE.equals(fields.get("isAnimated")));
            case "locationMessage", "liveLocationMessage" -> new Location(decimal(fields, "degreesLatitude"),
                    decimal(fields, "degreesLongitude"), text(fields, "name"), text(fields, "address"));
            case "contactMessage" -> new Contact(nonNull(List.of(first(fields, "displayName"))));
            case "contactsArrayMessage" -> new Contact(contactNames(fields));
            case "reactionMessage" -> new Reaction(fields.get("text") instanceof String s ? s : "",
                    fields.get("key") instanceof Map<?, ?> k ? text(k, "id") : null);
            case "pollCreationMessage", "pollCreationMessageV2", "pollCreationMessageV3" -> new Poll(
                    text(fields, "name"), optionNames(fields), integer(fields, "selectableOptionsCount") instanceof Integer n && n != 1);
            case "protocolMessage" -> "REVOKE".equals(String.valueOf(fields.get("type")))
                    ? new Revoked() : new Unknown(label(type));
            default -> new Unknown(label(type));
        };
    }

    private static List<String> contactNames(Map<?, ?> fields) {
        var names = new ArrayList<String>();
        if (fields.get("contacts") instanceof List<?> contacts) {
            for (var contact : contacts) {
                if (contact instanceof Map<?, ?> card && text(card, "displayName") instanceof String name) {
                    names.add(name);
                }
            }
        }
        if (names.isEmpty() && text(fields, "displayName") instanceof String name) {
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static List<String> optionNames(Map<?, ?> fields) {
        var names = new ArrayList<String>();
        if (fields.get("options") instanceof List<?> options) {
            for (var option : options) {
                if (option instanceof Map<?, ?> o && text(o, "optionName") instanceof String name) {
                    names.add(name);
                } else if (option instanceof String name && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    private static List<String> nonNull(List<String> values) {
        return values.stream().filter(v -> v != null).toList();
    }

    private static String first(Map<?, ?> fields, String... keys) {
        for (var key : keys) {
            var value = text(fields, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(Map<?, ?> fields, String key) {
        return fields.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private static Long number(Map<?, ?> fields, String key) {
        return switch (fields.get(key)) {
            case Number n -> n.longValue();
            case String s when s.matches("\\d{1,18}") -> Long.parseLong(s);   // o Baileys manda Long como texto
            case null, default -> null;
        };
    }

    private static Integer integer(Map<?, ?> fields, String key) {
        var value = number(fields, key);
        return value == null || value > Integer.MAX_VALUE ? null : value.intValue();
    }

    private static Double decimal(Map<?, ?> fields, String key) {
        return fields.get(key) instanceof Number n ? n.doubleValue() : null;
    }

    /** "fooMessage" → "foo"; sem tipo, "?". */
    static String label(String type) {
        if (type == null || type.isBlank()) {
            return "?";
        }
        return type.endsWith("Message") && type.length() > "Message".length()
                ? type.substring(0, type.length() - "Message".length()) : type;
    }

    /** 75 → "1:15"; null sem duração. */
    static String duration(Integer seconds) {
        if (seconds == null || seconds < 0) {
            return null;
        }
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    /** Em pt-BR: "820 B", "12 KB", "1,4 MB"; null sem tamanho. */
    static String size(Long bytes) {
        if (bytes == null || bytes < 0) {
            return null;
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        var units = bytes < 1024L * 1024 * 1024 ? new Object[] {bytes / (1024.0 * 1024), "MB"}
                : new Object[] {bytes / (1024.0 * 1024 * 1024), "GB"};
        return String.format(Locale.forLanguageTag("pt-BR"), "%.1f %s", units[0], units[1]);
    }
}
