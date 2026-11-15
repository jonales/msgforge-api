package dev.msgforge.web;

import dev.msgforge.access.Permission;
import dev.msgforge.chat.ChatDtos.KeyFilter;
import dev.msgforge.chat.ChatService;
import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.gateway.WhatsAppGateway.GatewayException;
import dev.msgforge.gateway.WhatsAppGateway.MediaTooLargeException;
import dev.msgforge.gateway.WhatsAppGateway.MessageKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * A mídia das mensagens do histórico, para a conversa do Manager mostrar (imagem, vídeo e áudio com
 * os controles do navegador; documento para baixar). O motor baixa e decifra em memória; nada vai
 * para o disco. Só mensagens que estão no histórico da instância, e só com "ver conversas".
 *
 * <p>A mídia vem de terceiros e é servida na origem do Manager. Só tipos que o navegador mostra sem
 * executar nada (imagem raster, áudio, vídeo) saem inline; o resto (SVG, HTML, PDF...) sai como
 * download, em {@code application/octet-stream}. Toda resposta leva {@code nosniff} e um CSP
 * {@code sandbox} próprio.
 */
@Controller
@RequestMapping("/manager/instances/{name}/messages")
public class ManagerMediaController {

    private static final Logger log = LoggerFactory.getLogger(ManagerMediaController.class);

    /** Teto do que o Manager baixa de uma vez (em memória). Acima disso, a tela fica com o marcador. */
    static final long MAX_BYTES = 25L * 1024 * 1024;

    /** Mostrados inline: o navegador só decodifica, sem script nem documento ativo. */
    static final Set<String> INLINE = Set.of("image/jpeg", "image/png", "image/webp", "image/gif",
            "audio/ogg", "audio/mpeg", "audio/mp4", "audio/aac", "audio/amr", "audio/wav", "audio/webm",
            "video/mp4", "video/webm", "video/3gpp");

    private final ManagerAccess guard;
    private final ChatService chats;

    public ManagerMediaController(ManagerAccess guard, ChatService chats) {
        this.guard = guard;
        this.chats = chats;
    }

    @GetMapping("/media")
    public ResponseEntity<byte[]> media(@PathVariable String name, @RequestParam String jid, @RequestParam String id,
                                        @RequestParam(defaultValue = "false") boolean fromMe) {
        try {
            guard.require(name, Permission.CHAT_VIEW);
        } catch (NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var stored = chats.findMessages(name, new KeyFilter(jid, fromMe, id), 1, 1).records();
        if (stored.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var record = stored.getFirst();
        var content = MessageContent.of(record.messageType(), record.message());
        if (!content.media()) {
            return ResponseEntity.notFound().build();
        }
        try {
            return chats.downloadMedia(name, new MessageKey(record.key().remoteJid(), id, fromMe), MAX_BYTES)
                    .map(media -> respond(media.bytes(), declaredType(content, media.mimeType()), fileName(content)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (MediaTooLargeException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        } catch (BadRequestException | GatewayException e) {
            // Desconectada, ou o WhatsApp não entregou: a tela fica com o marcador do tipo
            log.debug("Mídia {} de {} indisponível: {}", id, name, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private static ResponseEntity<byte[]> respond(byte[] bytes, String declared, String fileName) {
        var type = safeType(declared);
        var inline = !MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(type) && fileName == null;
        var disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(fileName != null ? fileName : "midia", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                // A conversa é trocada a cada poucos segundos: o navegador reaproveita sem baixar de novo
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(bytes);
    }

    /** O tipo que a mensagem declara, sem parâmetros e em minúsculas; figurinha sem tipo é WebP. */
    private static String declaredType(MessageContent content, String fromGateway) {
        var declared = fromGateway;
        if (declared == null) {
            declared = switch (content) {
                case MessageContent.Image i -> i.mimetype();
                case MessageContent.Document d -> d.mimetype();
                case MessageContent.Sticker _ -> "image/webp";
                default -> null;
            };
        }
        return declared;
    }

    /** O tipo de mídia na resposta: o declarado, se for um dos {@link #INLINE}; senão, binário. */
    static String safeType(String declared) {
        if (declared == null) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        var base = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return INLINE.contains(base) ? base : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    /** Documento sai como download com o nome dele; o resto, inline. */
    private static String fileName(MessageContent content) {
        if (content instanceof MessageContent.Document document) {
            return document.fileName() != null ? document.fileName() : "documento";
        }
        return null;
    }
}
