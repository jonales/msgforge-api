package dev.msgforge.gateway.cobalt;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.event.GatewayEvent;
import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.gateway.session.SessionStore;
import dev.msgforge.instance.ConnectionState;
import dev.msgforge.instance.Instance;
import dev.msgforge.security.PrivateNetworkGuard;
import it.auties.whatsapp.api.DisconnectReason;
import it.auties.whatsapp.api.ErrorHandler;
import it.auties.whatsapp.api.PairingCodeHandler;
import it.auties.whatsapp.api.QrHandler;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.model.contact.ContactStatus;
import it.auties.whatsapp.model.info.ChatMessageInfo;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.message.model.Message;
import it.auties.protobuf.model.ProtobufString;
import it.auties.whatsapp.model.message.model.ChatMessageKeyBuilder;
import it.auties.whatsapp.model.message.standard.AudioMessageSimpleBuilder;
import it.auties.whatsapp.model.message.standard.ContactMessage;
import it.auties.whatsapp.model.message.standard.ContactMessageBuilder;
import it.auties.whatsapp.model.message.standard.ContactsMessageBuilder;
import it.auties.whatsapp.model.message.standard.LocationMessageBuilder;
import it.auties.whatsapp.model.message.standard.PollCreationMessage;
import it.auties.whatsapp.model.message.standard.PollCreationMessageBuilder;
import it.auties.whatsapp.model.message.standard.ReactionMessageBuilder;
import it.auties.whatsapp.model.poll.PollOption;
import it.auties.whatsapp.model.poll.PollOptionBuilder;
import it.auties.whatsapp.model.message.standard.DocumentMessageSimpleBuilder;
import it.auties.whatsapp.model.message.standard.ImageMessageSimpleBuilder;
import it.auties.whatsapp.model.message.standard.VideoMessageSimpleBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Motor real do WhatsApp sobre o Cobalt 0.0.10 (ADR 0007), ativo com {@code MSGFORGE_GATEWAY=cobalt}.
 *
 * <p>Uma conexão do Cobalt por instância, identificada pelo id da instância. A sessão (chaves e
 * store) vai para o {@code session_store} pelo {@link DatabaseSessionSerializer}. Os callbacks do
 * Cobalt viram {@link GatewayEvent}; daí em diante o {@code EventDispatcher} cuida de webhook e
 * RabbitMQ, como com o stub.
 *
 * <p><b>Não validado ao vivo ainda</b> (o proxy da nuvem bloqueia o WhatsApp): ver
 * {@code docs/spikes/cobalt/README.md}.
 */
@Component
@ConditionalOnProperty(name = "msgforge.gateway", havingValue = "cobalt")
public class CobaltWhatsAppGateway implements WhatsAppGateway, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CobaltWhatsAppGateway.class);

    /** Códigos de statusReason no padrão do Baileys. */
    static final int REASON_OK = 200;
    static final int REASON_LOGGED_OUT = 401;
    static final int REASON_BANNED = 403;
    static final int REASON_CONNECTION_CLOSED = 428;

    private static final ConnectResult NO_CODE = new ConnectResult(null, null, null);
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final long MAX_TYPING_DELAY_MS = 20_000;

    /** Estado de uma instância conectada (ou conectando). */
    private static final class Connection {
        final String instanceName;
        /** Conexão pedida com pairing code (número informado) em vez de QR. */
        final boolean pairing;
        /** Completa com o primeiro QR/pairing code, no login ou na falha. */
        final CompletableFuture<ConnectResult> firstCode = new CompletableFuture<>();
        volatile Whatsapp api;
        /** Último QR/pairing code gerado, para quem chamar connect de novo enquanto espera o scan. */
        volatile ConnectResult lastCode;
        volatile boolean closingOnPurpose;

        Connection(String instanceName, boolean pairing) {
            this.instanceName = instanceName;
            this.pairing = pairing;
        }

        void codeGenerated(ConnectResult code) {
            lastCode = code;
            firstCode.complete(code);
        }
    }

    private final ConcurrentHashMap<UUID, Connection> connections = new ConcurrentHashMap<>();
    /** Serializa connect/logout por instância: nunca dois clientes do WhatsApp para a mesma sessão. */
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher events;
    private final SessionStore sessions;
    private final DatabaseSessionSerializer serializer;
    private final PrivateNetworkGuard guard;
    private final MsgforgeProperties.Cobalt config;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;

    public CobaltWhatsAppGateway(ApplicationEventPublisher events, SessionStore sessions,
                                 PrivateNetworkGuard guard, MsgforgeProperties properties) {
        this.events = events;
        this.sessions = sessions;
        this.guard = guard;
        this.config = properties.cobalt();
        this.serializer = new DatabaseSessionSerializer(sessions, executor);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(guard.enabled() ? HttpClient.Redirect.NEVER : HttpClient.Redirect.NORMAL)
                .build();
    }

    // ---------------------------------------------------------------- conexão

    @Override
    public ConnectResult connect(Instance instance, String phoneNumberForPairing) {
        var id = instance.getId();
        var wantsPairing = phoneNumberForPairing != null && !phoneNumberForPairing.isBlank();
        Connection connection;
        synchronized (lock(id)) {
            var existing = connections.get(id);
            if (existing != null && existing.api.isConnected()) {
                return NO_CODE;   // já conectada: nada a escanear
            }
            if (existing != null && !wantsPairing && !existing.pairing) {
                connection = existing;   // já esperando o scan: devolve o QR atual, não gera outro
            } else {
                if (existing != null) {
                    close(id, existing);   // pedido diferente (ex.: agora com pairing): recomeça
                }
                connection = start(instance, wantsPairing ? phoneNumberForPairing : null);
            }
        }
        var current = connection.lastCode;
        if (current != null) {
            return current;
        }
        try {
            return connection.firstCode.get(config.qrWaitMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return NO_CODE;   // o QR chega depois pelo webhook qrcode.updated
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NO_CODE;
        } catch (ExecutionException e) {
            return NO_CODE;
        }
    }

    /** Cria o cliente do Cobalt e começa a conectar. Chamado com o lock da instância. */
    private Connection start(Instance instance, String phoneNumberForPairing) {
        var id = instance.getId();
        var name = instance.getName();
        var connection = new Connection(name, phoneNumberForPairing != null);
        var options = Whatsapp.webBuilder()
                .serializer(serializer)
                .newConnection(id)
                .name(config.deviceName())
                .errorHandler((api, location, error) -> {
                    log.warn("[cobalt] {}: erro em {}: {}", name, location, error.toString());
                    return ErrorHandler.Result.DISCARD;
                });
        if (config.proxy() != null && !config.proxy().isBlank()) {
            options.proxy(URI.create(config.proxy()));
        }

        var restored = sessions.exists(id) ? options.registered() : Optional.<Whatsapp>empty();
        if (restored.isPresent()) {
            connection.api = restored.get();   // sessão salva: reconecta sem QR
        } else if (phoneNumberForPairing != null) {
            connection.api = options.unregistered(pairingNumber(phoneNumberForPairing), (PairingCodeHandler) code -> {
                events.publishEvent(new GatewayEvent.QrCodeUpdated(name, null, code));
                connection.codeGenerated(new ConnectResult(code, null, null));
            });
        } else {
            var count = new AtomicInteger();
            connection.api = options.unregistered((QrHandler) qr -> {
                var base64 = QrImages.toDataUrl(qr);
                log.info("[cobalt] {}: QR #{} gerado", name, count.incrementAndGet());
                events.publishEvent(new GatewayEvent.QrCodeUpdated(name, qr, null, base64));
                connection.codeGenerated(new ConnectResult(null, qr, base64));
            });
        }

        registerListeners(id, connection);
        connections.put(id, connection);
        events.publishEvent(new GatewayEvent.ConnectionUpdated(name, ConnectionState.CONNECTING, null));

        connection.api.connect().whenComplete((ignored, error) -> {
            if (error != null) {
                log.warn("[cobalt] {}: falha ao conectar: {}", name, rootMessage(error));
                if (connections.remove(id, connection)) {
                    events.publishEvent(new GatewayEvent.ConnectionUpdated(
                            name, ConnectionState.CLOSE, REASON_CONNECTION_CLOSED));
                }
                connection.firstCode.complete(NO_CODE);
            }
        });
        return connection;
    }

    private void registerListeners(UUID id, Connection connection) {
        var name = connection.instanceName;
        var api = connection.api;
        api.addLoggedInListener(whatsapp -> {
            var wuid = whatsapp.store().jid().map(jid -> jid.toSimpleJid().toString()).orElse(null);
            var profile = whatsapp.store().name();
            // Antes de o servidor informar o nome do perfil, o Store guarda o nome do aparelho
            var profileName = profile == null || profile.equals(config.deviceName()) ? null : profile;
            log.info("[cobalt] {}: conectada como {}", name, wuid);
            events.publishEvent(new GatewayEvent.ConnectionUpdated(name, ConnectionState.OPEN, REASON_OK,
                    wuid, profileName));
            connection.lastCode = null;   // conectada: não há mais QR para mostrar
            connection.firstCode.complete(NO_CODE);
        });
        api.addDisconnectedListener(reason -> onDisconnected(id, connection, reason));
        api.addNewChatMessageListener((ChatMessageInfo message) -> CobaltMessageMapper.upsert(message, id)
                .ifPresent(data -> events.publishEvent(new GatewayEvent.MessageReceived(name, data))));
        api.addMessageStatusListener((MessageInfo<?> info) -> CobaltMessageMapper.statusUpdate(info, id)
                .ifPresent(data -> events.publishEvent(new GatewayEvent.MessageStatusUpdated(name, data))));
    }

    private void onDisconnected(UUID id, Connection connection, DisconnectReason reason) {
        if (connection.closingOnPurpose) {
            return;   // logout/close pedido por nós: o evento já foi publicado lá
        }
        var name = connection.instanceName;
        log.info("[cobalt] {}: desconectada ({})", name, reason);
        switch (reason) {
            case RECONNECTING -> events.publishEvent(
                    new GatewayEvent.ConnectionUpdated(name, ConnectionState.CONNECTING, REASON_CONNECTION_CLOSED));
            case LOGGED_OUT -> {
                connections.remove(id, connection);
                sessions.delete(id);   // desconectado pelo celular: a sessão não vale mais
                events.publishEvent(new GatewayEvent.ConnectionUpdated(name, ConnectionState.CLOSE, REASON_LOGGED_OUT));
            }
            case BANNED -> {
                connections.remove(id, connection);
                events.publishEvent(new GatewayEvent.ConnectionUpdated(name, ConnectionState.CLOSE, REASON_BANNED));
            }
            case DISCONNECTED -> {
                connections.remove(id, connection);
                events.publishEvent(new GatewayEvent.ConnectionUpdated(
                        name, ConnectionState.CLOSE, REASON_CONNECTION_CLOSED));
            }
        }
    }

    @Override
    public void logout(Instance instance) {
        Connection connection;
        synchronized (lock(instance.getId())) {
            connection = connections.remove(instance.getId());
            if (connection != null) {
                connection.closingOnPurpose = true;
            }
        }
        if (connection != null) {
            await(connection.api.logout(), "logout");
        }
        events.publishEvent(new GatewayEvent.ConnectionUpdated(instance.getName(), ConnectionState.CLOSE,
                REASON_LOGGED_OUT));
    }

    /** Desliga sem deslogar (shutdown da aplicação): a sessão continua válida para o próximo boot. */
    private void close(UUID id, Connection connection) {
        connection.closingOnPurpose = true;
        connections.remove(id, connection);
        try {
            connection.api.disconnect().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("[cobalt] {}: disconnect: {}", connection.instanceName, rootMessage(e));
        }
    }

    private Object lock(UUID id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }

    /** Conexões registradas agora (para testes). */
    int activeConnections() {
        return connections.size();
    }

    @Override
    public void destroy() {
        connections.forEach(this::close);
        executor.shutdown();
    }

    // ---------------------------------------------------------------- envio

    @Override
    public SentMessage sendText(Instance instance, OutgoingText text) {
        var connection = requireConnected(instance);
        var jid = Jid.of(text.jid());
        typing(connection, jid, text.delayMs());
        var sent = await(connection.api.sendMessage(jid, text.text()), "envio de texto");
        return sentMessage(sent, text.jid());
    }

    @Override
    public SentMessage sendMedia(Instance instance, OutgoingMedia media) {
        var connection = requireConnected(instance);
        var bytes = loadMedia(media.media());
        var jid = Jid.of(media.jid());
        typing(connection, jid, media.delayMs());
        var sent = await(connection.api.sendMessage(jid, buildMedia(media, bytes)), "envio de mídia");
        return sentMessage(sent, media.jid());
    }

    @Override
    public SentMessage send(Instance instance, Outgoing message) {
        var connection = requireConnected(instance);
        var audio = message instanceof VoiceNote voice ? loadMedia(voice.audio()) : null;
        var jid = Jid.of(message.jid());
        typing(connection, jid, message.delayMs());
        var sent = await(connection.api.sendMessage(jid, buildMessage(message, audio)), "envio");
        return sentMessage(sent, message.jid());
    }

    /** Monta a mensagem do Cobalt. {@code audio} só é usado para VoiceNote (bytes já carregados). */
    static Message buildMessage(Outgoing message, byte[] audio) {
        return switch (message) {
            // Mensagem de voz: o WhatsApp espera OGG/Opus; outros formatos são enviados como estão
            case VoiceNote _ -> new AudioMessageSimpleBuilder().media(audio)
                    .mimeType("audio/ogg; codecs=opus").voiceMessage(true).build();
            case Location location -> new LocationMessageBuilder()
                    .latitude(location.latitude()).longitude(location.longitude())
                    .name(location.name()).address(location.address()).build();
            case Contacts contacts when contacts.contacts().size() == 1 -> contact(contacts.contacts().getFirst());
            case Contacts contacts -> new ContactsMessageBuilder()
                    .name(contacts.contacts().size() + " contacts")
                    .contacts(contacts.contacts().stream().map(CobaltWhatsAppGateway::contact).toList())
                    .build();
            case Reaction reaction -> new ReactionMessageBuilder()
                    .key(new ChatMessageKeyBuilder().chatJid(Jid.of(reaction.jid())).fromMe(reaction.fromMe())
                            .id(reaction.messageId()).build())
                    .content(reaction.emoji())
                    // O Cobalt chama o campo 4 de "timestampSeconds", mas no protocolo do WhatsApp ele é
                    // senderTimestampMs (milissegundos). Conferir no primeiro teste ao vivo.
                    .timestampSeconds(Instant.now().toEpochMilli())
                    .build();
            case Poll poll -> poll(poll);
        };
    }

    private static ContactMessage contact(ContactCard card) {
        return new ContactMessageBuilder().name(card.displayName()).vcard(ProtobufString.wrap(card.vcard())).build();
    }

    /**
     * Mesmo procedimento do {@code PollCreationMessage.simpleBuilder} do Cobalt (chave aleatória e
     * opções indexadas pelo SHA-256 do nome), mas respeitando o {@code selectableCount} pedido:
     * o simpleBuilder sempre fixa "várias escolhas".
     */
    static PollCreationMessage poll(Poll poll) {
        var options = poll.values().stream().map(v -> new PollOptionBuilder().name(v).build()).toList();
        var byHash = new java.util.LinkedHashMap<String, PollOption>();
        for (var option : options) {
            byHash.put(sha256Hex(option.name()), option);
        }
        var key = new byte[32];
        RANDOM.nextBytes(key);
        return new PollCreationMessageBuilder()
                .encryptionKey(key)
                .title(poll.name())
                .selectableOptions(options)
                .selectableOptionsCount(poll.selectableCount())
                .selectableOptionsMap(byHash)
                .build();
    }

    private static String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Message buildMedia(OutgoingMedia media, byte[] bytes) {
        return switch (media.mediaType()) {
            case "image" -> new ImageMessageSimpleBuilder().media(bytes).caption(media.caption())
                    .mimeType(media.mimeType()).build();
            case "video" -> new VideoMessageSimpleBuilder().media(bytes).caption(media.caption())
                    .mimeType(media.mimeType()).build();
            case "audio" -> new AudioMessageSimpleBuilder().media(bytes).mimeType(media.mimeType()).build();
            case "document" -> new DocumentMessageSimpleBuilder().media(bytes)
                    .fileName(media.fileName() != null ? media.fileName() : "file")
                    .title(media.caption()).mimeType(media.mimeType()).build();
            default -> throw new BadRequestException("Unsupported mediatype: " + media.mediaType());
        };
    }

    /** {@code delay} da API: mostra "digitando..." por esse tempo antes de enviar. */
    private void typing(Connection connection, Jid jid, Integer delayMs) {
        if (delayMs == null || delayMs <= 0) {
            return;
        }
        try {
            connection.api.changePresence(jid, ContactStatus.COMPOSING).get(5, TimeUnit.SECONDS);
            Thread.sleep(Math.min(delayMs, MAX_TYPING_DELAY_MS));
            connection.api.changePresence(jid, ContactStatus.AVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("[cobalt] {}: presença: {}", connection.instanceName, rootMessage(e));
        }
    }

    /** Mídia da API: URL http(s) ou base64 (com ou sem prefixo data:). */
    byte[] loadMedia(String media) {
        if (media.startsWith("http://") || media.startsWith("https://")) {
            guard.check(media).ifPresent(reason -> {
                throw new BadRequestException("Media url not allowed: " + reason);
            });
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create(media))
                                .timeout(Duration.ofMillis(config.sendTimeoutMs())).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() / 100 != 2) {
                    response.body().close();
                    throw new BadRequestException("Could not download media: HTTP " + response.statusCode());
                }
                try (var body = response.body()) {
                    var bytes = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, config.mediaMaxBytes() + 1));
                    return checkSize(bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GatewayException("Media download interrupted", e);
            } catch (java.io.IOException | IllegalArgumentException e) {
                throw new BadRequestException("Could not download media: " + e.getMessage());
            }
        }
        var base64 = media.startsWith("data:") && media.contains(",") ? media.substring(media.indexOf(',') + 1) : media;
        try {
            return checkSize(Base64.getMimeDecoder().decode(base64));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("media must be an http(s) url or base64");
        }
    }

    private byte[] checkSize(byte[] bytes) {
        if (bytes.length == 0) {
            throw new BadRequestException("media is empty");
        }
        if (bytes.length > config.mediaMaxBytes()) {
            throw new BadRequestException("media is larger than " + config.mediaMaxBytes() + " bytes");
        }
        return bytes;
    }

    private Connection requireConnected(Instance instance) {
        var connection = connections.get(instance.getId());
        if (connection == null || !connection.api.isConnected()) {
            throw new BadRequestException("The \"" + instance.getName() + "\" instance is not connected");
        }
        return connection;
    }

    private SentMessage sentMessage(MessageInfo<?> sent, String remoteJid) {
        var timestamp = sent.timestampSeconds().orElse(Instant.now().getEpochSecond());
        return new SentMessage(sent.id(), remoteJid, timestamp);
    }

    private <T> T await(CompletableFuture<T> future, String what) {
        try {
            return future.get(config.sendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(what + " interrompido", e);
        } catch (TimeoutException e) {
            throw new GatewayException(what + ": o WhatsApp não respondeu em " + config.sendTimeoutMs() + " ms", e);
        } catch (ExecutionException e) {
            throw new GatewayException(what + " falhou: " + rootMessage(e), e.getCause());
        }
    }

    /** O pairing code exige só os dígitos do número (E.164 sem +). */
    static long pairingNumber(String phone) {
        var digits = phone.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) {
            throw new BadRequestException("Invalid number for pairing code: \"" + phone + "\"");
        }
        return Long.parseLong(digits);
    }

    private static String rootMessage(Throwable error) {
        var root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
