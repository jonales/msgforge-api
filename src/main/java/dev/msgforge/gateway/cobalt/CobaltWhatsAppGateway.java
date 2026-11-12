package dev.msgforge.gateway.cobalt;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.common.ApiExceptionHandler.NumberNotOnWhatsAppException;
import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.event.GatewayEvent;
import dev.msgforge.gateway.Jids;
import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.gateway.media.AudioTranscoder;
import dev.msgforge.gateway.media.MediaSignatures;
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
import it.auties.whatsapp.model.chat.ChatMetadata;
import it.auties.whatsapp.model.chat.ChatSettingPolicy;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.jid.JidProvider;
import it.auties.whatsapp.model.jid.JidServer;
import it.auties.whatsapp.model.message.model.MessageStatus;
import it.auties.whatsapp.model.node.Node;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
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
    /** Diagnóstico do protocolo: LOGGING_LEVEL_DEV_MSGFORGE_GATEWAY_COBALT_WIRE=DEBUG. */
    private static final Logger wire = LoggerFactory.getLogger("dev.msgforge.gateway.cobalt.wire");

    /** Códigos de statusReason no padrão do Baileys. */
    static final int REASON_OK = 200;
    static final int REASON_LOGGED_OUT = 401;
    static final int REASON_BANNED = 403;
    static final int REASON_CONNECTION_CLOSED = 428;

    private static final ConnectResult NO_CODE = new ConnectResult(null, null, null);
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final long MAX_TYPING_DELAY_MS = 20_000;
    /** Quanto esperar o ack do próprio Cobalt antes de o OfflineQueueGuard confirmar a mensagem. */
    private static final long OFFLINE_ACK_DELAY_MS = 15_000;

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
        /**
         * Logada no WhatsApp. Não use {@code api.isConnected()}: no Cobalt ele vira true logo após o
         * handshake do socket, antes do scan do QR.
         */
        volatile boolean loggedIn;
        /** LID → número, aprendido dos nós recebidos. */
        final LidResolver lids = new LidResolver();
        /** messages.update das mensagens enviadas (o listener do Cobalt não dispara com LID). */
        volatile SentStatusTracker statuses;

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
    private final AudioTranscoder transcoder;
    private final MsgforgeProperties.Cobalt config;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("cobalt-offline-guard").factory());
    private final HttpClient http;
    private final WebVersion webVersion;

    public CobaltWhatsAppGateway(ApplicationEventPublisher events, SessionStore sessions,
                                 PrivateNetworkGuard guard, AudioTranscoder transcoder,
                                 MsgforgeProperties properties) {
        this.events = events;
        this.sessions = sessions;
        this.guard = guard;
        this.transcoder = transcoder;
        this.config = properties.cobalt();
        this.webVersion = new WebVersion(config.webVersion(), config.proxy(), java.time.Clock.systemUTC());
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
            if (existing != null && existing.loggedIn) {
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
        webVersion.apply();   // antes do builder: o Cobalt lê a versão no login
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
            whatsapp.store().lid().ifPresent(lid -> whatsapp.store().jid()
                    .ifPresent(phone -> connection.lids.learn(lid, phone)));
            // Antes do evento: quem reage ao connection.update "open" já pode enviar
            connection.loggedIn = true;
            connection.lastCode = null;   // conectada: não há mais QR para mostrar
            events.publishEvent(new GatewayEvent.ConnectionUpdated(name, ConnectionState.OPEN, REASON_OK,
                    wuid, profileName));
            connection.firstCode.complete(NO_CODE);
        });
        api.addDisconnectedListener(reason -> onDisconnected(id, connection, reason));
        var statuses = new SentStatusTracker(id,
                data -> events.publishEvent(new GatewayEvent.MessageStatusUpdated(name, data)));
        connection.statuses = statuses;
        // Antes do listener de mensagens: o par LID ↔ número vem no nó, antes de a mensagem ser decodificada
        api.addNodeReceivedListener(node -> {
            connection.lids.observe(node);
            statuses.received(node);
        });
        api.addNewChatMessageListener((ChatMessageInfo message) -> {
            try {
                var data = CobaltMessageMapper.upsert(message, id, connection.lids);
                wire.debug("{}: nova mensagem id={} chat={} sender={} tipo={} -> {}", name, message.id(),
                        message.chatJid(), message.senderJid(), message.message().unbox().type(),
                        data.isPresent() ? "messages.upsert" : "ignorada");
                data.ifPresent(d -> events.publishEvent(new GatewayEvent.MessageReceived(name, d)));
            } catch (RuntimeException e) {
                log.warn("[cobalt] {}: falha ao converter a mensagem {}: {}", name, message.id(), e.toString());
            }
        });
        api.addMessageStatusListener((MessageInfo<?> info) -> {
            try {
                var data = CobaltMessageMapper.statusUpdate(info, id, connection.lids)
                        .filter(d -> statuses.advanceFromCobalt(info.id(), String.valueOf(d.get("status"))));
                wire.debug("{}: status (Cobalt) id={} -> {}", name, info.id(),
                        data.isPresent() ? "messages.update" : "ignorado");
                data.ifPresent(d -> events.publishEvent(new GatewayEvent.MessageStatusUpdated(name, d)));
            } catch (RuntimeException e) {
                log.warn("[cobalt] {}: falha ao converter o status {}: {}", name, info.id(), e.toString());
            }
        });
        var offlineGuard = new OfflineQueueGuard(name, scheduler, OFFLINE_ACK_DELAY_MS,
                node -> RawNodes.send(api, node));
        api.addNodeReceivedListener(offlineGuard::received);
        api.addNodeSentListener(offlineGuard::sent);
        if (wire.isDebugEnabled()) {
            // Só atributos (de, para, id, tipo); o conteúdo vem cifrado e não é registrado
            api.addNodeReceivedListener(node -> wire.debug("{}: <- {}", name, outline(node)));
            api.addNodeSentListener(node -> wire.debug("{}: -> {}", name, outline(node)));
        }
    }

    /** Nó em árvore, só nomes e atributos (nunca o conteúdo, que pode ser cifrado ou pessoal). */
    private static String outline(Node node) {
        var out = new StringBuilder();
        outline(node, out, 0);
        return out.toString();
    }

    private static void outline(Node node, StringBuilder out, int depth) {
        out.append(node.description()).append(node.attributes().toMap());
        var children = node.children();
        if (children.isEmpty() || depth >= 6) {
            return;
        }
        out.append(" [");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            outline(children.get(i), out, depth + 1);
        }
        out.append(']');
    }

    private void onDisconnected(UUID id, Connection connection, DisconnectReason reason) {
        connection.loggedIn = false;
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
        scheduler.shutdownNow();
    }

    // ---------------------------------------------------------------- envio

    @Override
    public SentMessage sendText(Instance instance, OutgoingText text) {
        var connection = requireConnected(instance);
        var jid = resolve(connection, text.jid());
        typing(connection, jid, text.delayMs());
        var sent = deliver(() -> connection.api.sendMessage(jid, text.text()), "envio de texto");
        return sentMessage(instance, connection, sent, jid.toString());
    }

    @Override
    public SentMessage sendMedia(Instance instance, OutgoingMedia media) {
        var connection = requireConnected(instance);
        var bytes = loadMedia(media.media());
        if ("video".equals(media.mediaType()) && !MediaSignatures.isVideo(bytes)) {
            throw new BadRequestException("media is not a supported video (MP4, 3GP or WebM)");
        }
        var jid = resolve(connection, media.jid());
        typing(connection, jid, media.delayMs());
        var sent = deliver(() -> connection.api.sendMessage(jid, buildMedia(media, bytes)), "envio de mídia");
        return sentMessage(instance, connection, sent, jid.toString());
    }

    @Override
    public SentMessage send(Instance instance, Outgoing message) {
        var connection = requireConnected(instance);
        var audio = message instanceof VoiceNote voice ? voiceAudio(voice) : null;
        var jid = resolve(connection, message.jid());
        typing(connection, jid, message.delayMs());
        var sent = deliver(() -> connection.api.sendMessage(jid, buildMessage(message, audio)), "envio");
        return sentMessage(instance, connection, sent, jid.toString());
    }

    /** Carrega o áudio e, se pedido e necessário, converte para OGG/Opus. */
    byte[] voiceAudio(VoiceNote voice) {
        var audio = loadMedia(voice.audio());
        if (!voice.encode() || AudioTranscoder.isOggOpus(audio)) {
            return audio;
        }
        if (!transcoder.available()) {
            return audio;   // sem ffmpeg: envia como veio (o aviso sai uma vez no log)
        }
        return transcoder.toOggOpus(audio);
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

    @Override
    public Map<String, String> checkNumbers(Instance instance, List<String> jids) {
        return resolveMany(requireConnected(instance), jids);
    }

    /**
     * Versão em lote do {@link #resolve}: para cada JID, o que o WhatsApp conhece ou null. Grupos e
     * outros servidores passam como estão. Limpa o cache envenenado antes (ver UsersCache).
     */
    private Map<String, String> resolveMany(Connection connection, List<String> jids) {
        var numbers = jids.stream().filter(jid -> Jid.of(jid).hasServer(JidServer.whatsapp())).distinct().toList();
        var exists = new HashMap<String, Boolean>();
        if (!numbers.isEmpty()) {
            var query = numbers.stream().map(Jid::of).toArray(Jid[]::new);
            for (var jid : query) {
                UsersCache.forget(jid);
            }
            var found = await(connection.api.hasWhatsapp(query), "consulta de números");
            found.forEach((jid, has) -> exists.put(connection.lids.apply(jid), has));
        }
        var resolved = resolveNumbers(numbers, exists);
        var result = new LinkedHashMap<String, String>();
        jids.forEach(jid -> result.put(jid, Jid.of(jid).hasServer(JidServer.whatsapp()) ? resolved.get(jid) : jid));
        return result;
    }

    /** Participantes de grupo: o JID que o WhatsApp conhece, ou o pedido (o WhatsApp recusa sozinho). */
    private Jid[] participants(Connection connection, List<String> jids) {
        var known = resolveMany(connection, jids);
        return jids.stream().map(jid -> Jid.of(known.get(jid) != null ? known.get(jid) : jid)).toArray(Jid[]::new);
    }

    /**
     * O Cobalt chaveia a resposta pelo JID que o WhatsApp devolve, que pode ser o celular
     * brasileiro sem (ou com) o nono dígito. Sem o JID consultado na resposta, vale a forma
     * alternativa.
     */
    static Map<String, String> resolveNumbers(List<String> queried, Map<String, Boolean> found) {
        var result = new LinkedHashMap<String, String>();
        for (var jid : queried) {
            String known = null;
            if (Boolean.TRUE.equals(found.get(jid))) {
                known = jid;
            } else {
                known = Jids.brazilianMobileAlternative(jid).filter(alt -> Boolean.TRUE.equals(found.get(alt)))
                        .orElse(null);
            }
            result.put(jid, known);
        }
        return result;
    }

    /**
     * Mensagem conhecida no Store: lida uma a uma. Desconhecida (chegou antes desta sessão, por
     * exemplo): a conversa inteira é marcada como lida. As enviadas por nós são ignoradas.
     */
    @Override
    public void markRead(Instance instance, List<MessageKey> keys) {
        var connection = requireConnected(instance);
        var wholeChats = new LinkedHashSet<String>();
        for (var key : keys) {
            if (key.fromMe()) {
                continue;
            }
            var message = connection.api.store().findMessageById(Jid.of(key.remoteJid()), key.id());
            if (message.isPresent() && message.get() instanceof ChatMessageInfo info) {
                await(connection.api.markMessageRead(info), "leitura");
            } else {
                wholeChats.add(key.remoteJid());
            }
        }
        for (var chat : wholeChats) {
            await(connection.api.markChatRead(Jid.of(chat)), "leitura");
        }
    }

    @Override
    public void sendPresence(Instance instance, String jid, Presence presence, Integer delayMs) {
        var connection = requireConnected(instance);
        var target = resolve(connection, jid);
        await(connection.api.changePresence(target, contactStatus(presence)), "presença");
        var temporary = presence == Presence.COMPOSING || presence == Presence.RECORDING;
        if (temporary && delayMs != null && delayMs > 0) {
            try {
                Thread.sleep(Math.min(delayMs, MAX_TYPING_DELAY_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            await(connection.api.changePresence(target, ContactStatus.AVAILABLE), "presença");
        }
    }

    /** O Cobalt não tem "paused": voltar a AVAILABLE encerra o "digitando...". */
    static ContactStatus contactStatus(Presence presence) {
        return switch (presence) {
            case AVAILABLE, PAUSED -> ContactStatus.AVAILABLE;
            case UNAVAILABLE -> ContactStatus.UNAVAILABLE;
            case COMPOSING -> ContactStatus.COMPOSING;
            case RECORDING -> ContactStatus.RECORDING;
        };
    }

    @Override
    public Profile fetchProfile(Instance instance, String jid) {
        var connection = requireConnected(instance);
        var resolved = resolveOrNull(connection, jid);
        if (resolved == null) {
            return new Profile(jid, false, null, null, null);
        }
        var target = resolved;
        jid = connection.lids.apply(resolved);
        var self = connection.api.store().jid().map(connection.lids::apply).orElse(null);
        var name = jid.equals(self)
                ? connection.api.store().name()
                : connection.api.store().findContactByJid(target)
                        .flatMap(contact -> contact.chosenName().or(contact::fullName))
                        .orElse(null);
        var status = optional(connection, connection.api.queryAbout(target))
                .flatMap(about -> about).flatMap(about -> about.about()).orElse(null);
        return new Profile(jid, true, name, profilePictureUrl(connection, target), status);
    }

    @Override
    public String profilePictureUrl(Instance instance, String jid) {
        var connection = requireConnected(instance);
        var resolved = resolveOrNull(connection, jid);
        return resolved == null ? null : profilePictureUrl(connection, resolved);
    }

    /** {@link #resolve}, mas "não tem WhatsApp" vira null em vez de 400. */
    private Jid resolveOrNull(Connection connection, String jid) {
        try {
            return resolve(connection, jid);
        } catch (dev.msgforge.common.ApiExceptionHandler.NumberNotOnWhatsAppException e) {
            return null;
        }
    }

    private String profilePictureUrl(Connection connection, Jid jid) {
        return optional(connection, connection.api.queryPicture(jid))
                .flatMap(uri -> uri).map(URI::toString).orElse(null);
    }

    /** Foto e recado podem ser escondidos pela privacidade: a falha vira "sem valor", não erro. */
    private <T> Optional<T> optional(Connection connection, CompletableFuture<T> future) {
        try {
            return Optional.ofNullable(await(future, "consulta de perfil"));
        } catch (GatewayException e) {
            log.debug("[cobalt] {}: {}", connection.instanceName, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void updateProfile(Instance instance, ProfileUpdate update) {
        var connection = requireConnected(instance);
        switch (update) {
            case ProfileName name -> await(connection.api.changeName(name.name()), "alteração do nome");
            case ProfileStatus status -> await(connection.api.changeAbout(status.status()), "alteração do recado");
            case ProfilePicture picture -> {
                var bytes = loadMedia(picture.picture());
                if (!MediaSignatures.isImage(bytes)) {
                    throw new BadRequestException("picture must be a JPEG or PNG image");
                }
                await(connection.api.changeProfilePicture(bytes), "alteração da foto");
            }
        }
    }

    // ---- Grupos

    @Override
    public GroupInfo createGroup(Instance instance, String subject, String description, List<String> participants) {
        var connection = requireConnected(instance);
        var members = participants(connection, participants);
        var created = await(connection.api.createGroup(subject, members), "criação do grupo")
                .orElseThrow(() -> new GatewayException("O WhatsApp não devolveu o grupo criado", null));
        if (description != null && !description.isBlank()) {
            await(connection.api.changeGroupDescription(created.jid(), description), "descrição do grupo");
            return groupInfo(connection, created.jid());
        }
        return groupInfo(created, connection.lids);
    }

    @Override
    public GroupInfo groupInfo(Instance instance, String groupJid) {
        return groupInfo(requireConnected(instance), Jid.of(groupJid));
    }

    private GroupInfo groupInfo(Connection connection, Jid groupJid) {
        return groupInfo(await(connection.api.queryGroupMetadata(groupJid), "consulta do grupo"), connection.lids);
    }

    /** Consulta os grupos do Store em paralelo; os que falharem (a conta saiu, por exemplo) ficam de fora. */
    @Override
    public List<GroupInfo> groups(Instance instance) {
        var connection = requireConnected(instance);
        var queries = connection.api.store().chats().stream()
                .filter(chat -> chat.isGroup())
                .map(chat -> connection.api.queryGroupMetadata(chat.jid())
                        .handle((metadata, error) -> error == null ? metadata : null))
                .toList();
        await(CompletableFuture.allOf(queries.toArray(CompletableFuture[]::new)), "consulta dos grupos");
        return queries.stream().map(CompletableFuture::join).filter(java.util.Objects::nonNull)
                .map(metadata -> groupInfo(metadata, connection.lids)).toList();
    }

    /** {@code names}: LID → número ({@link LidResolver}); nunca {@code toSimpleJid()} num LID. */
    static GroupInfo groupInfo(ChatMetadata metadata, java.util.function.Function<Jid, String> names) {
        var participants = metadata.participants().stream()
                .map(p -> new GroupParticipant(names.apply(p.jid()), switch (p.role()) {
                    case FOUNDER -> "superadmin";
                    case ADMIN -> "admin";
                    case USER -> null;
                }))
                .toList();
        return new GroupInfo(metadata.jid().toString(), metadata.subject(),
                metadata.subjectAuthorJid().map(names).orElse(null),
                metadata.subjectTimestampSeconds(), metadata.foundationTimestampSeconds(),
                metadata.founder().map(names).orElse(null),
                metadata.description().orElse(null),
                metadata.getPolicy(it.auties.whatsapp.model.chat.GroupSetting.SEND_MESSAGES)
                        .orElse(ChatSettingPolicy.ANYONE) == ChatSettingPolicy.ADMINS,
                metadata.getPolicy(it.auties.whatsapp.model.chat.GroupSetting.EDIT_GROUP_INFO)
                        .orElse(ChatSettingPolicy.ANYONE) == ChatSettingPolicy.ADMINS,
                participants);
    }

    @Override
    public String groupInviteCode(Instance instance, String groupJid) {
        return await(requireConnected(instance).api.queryGroupInviteCode(Jid.of(groupJid)), "convite do grupo");
    }

    @Override
    public String revokeGroupInvite(Instance instance, String groupJid) {
        var connection = requireConnected(instance);
        await(connection.api.revokeGroupInvite(Jid.of(groupJid)), "revogação do convite");
        return await(connection.api.queryGroupInviteCode(Jid.of(groupJid)), "convite do grupo");
    }

    @Override
    public String acceptGroupInvite(Instance instance, String inviteCode) {
        var chat = await(requireConnected(instance).api.acceptGroupInvite(inviteCode), "entrada no grupo")
                .orElseThrow(() -> new BadRequestException("Invalid invite code"));
        return chat.jid().toString();
    }

    @Override
    public List<String> updateParticipants(Instance instance, String groupJid, ParticipantAction action,
                                           List<String> participants) {
        var connection = requireConnected(instance);
        var group = Jid.of(groupJid);
        var members = participants(connection, participants);
        var future = switch (action) {
            case ADD -> connection.api.addGroupParticipants(group, members);
            case REMOVE -> connection.api.removeGroupParticipants(group, members);
            case PROMOTE -> connection.api.promoteGroupParticipants(group, members);
            case DEMOTE -> connection.api.demoteGroupParticipants(group, members);
        };
        var done = await(future, "participantes do grupo").stream().map(connection.lids::apply)
                .collect(java.util.stream.Collectors.toSet());
        // Devolve nos JIDs pedidos: o WhatsApp responde pelo JID resolvido (sem o 9, ou LID)
        var result = new java.util.ArrayList<String>();
        for (var i = 0; i < participants.size(); i++) {
            if (done.contains(connection.lids.apply(members[i]))) {
                result.add(participants.get(i));
            }
        }
        return result;
    }

    @Override
    public void updateGroup(Instance instance, String groupJid, GroupUpdate update) {
        var connection = requireConnected(instance);
        var group = Jid.of(groupJid);
        switch (update) {
            case GroupSubject subject -> await(connection.api.changeGroupSubject(group, subject.subject()), "assunto do grupo");
            case GroupDescription description ->
                    await(connection.api.changeGroupDescription(group, description.description()), "descrição do grupo");
            case GroupPicture picture -> {
                var bytes = loadMedia(picture.image());
                if (!MediaSignatures.isImage(bytes)) {
                    throw new BadRequestException("image must be a JPEG or PNG image");
                }
                await(connection.api.changeGroupPicture(group, bytes), "foto do grupo");
            }
            case GroupSetting setting -> await(connection.api.changeGroupSetting(group,
                    setting.setting() == Setting.SEND_MESSAGES
                            ? it.auties.whatsapp.model.chat.GroupSetting.SEND_MESSAGES
                            : it.auties.whatsapp.model.chat.GroupSetting.EDIT_GROUP_INFO,
                    setting.adminsOnly() ? ChatSettingPolicy.ADMINS : ChatSettingPolicy.ANYONE), "configuração do grupo");
        }
    }

    @Override
    public void leaveGroup(Instance instance, String groupJid) {
        await(requireConnected(instance).api.leaveGroup(Jid.of(groupJid)), "saída do grupo");
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
        if (connection == null || !connection.loggedIn) {
            throw new BadRequestException("The \"" + instance.getName() + "\" instance is not connected");
        }
        return connection;
    }

    /**
     * O JID para onde enviar, como o WhatsApp o conhece. Para números, consulta o servidor (como os
     * clientes oficiais): o JID devolvido pode ser outro (no Brasil, o número com o 9 extra resolve
     * para o JID sem ele). Sem JID na resposta, o número não tem WhatsApp.
     */
    private Jid resolve(Connection connection, String number) {
        var jid = Jid.of(number);
        if (!jid.hasServer(JidServer.whatsapp())) {
            return jid;   // grupo, newsletter...: sem consulta
        }
        UsersCache.forget(jid);   // o cache do Cobalt envenena (ver UsersCache)
        Map<Jid, Boolean> result = await(connection.api.hasWhatsapp(new JidProvider[] {jid}), "consulta do número");
        return result.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .map(entry -> entry.getKey().toSimpleJid())
                .findFirst()
                .orElseThrow(() -> new NumberNotOnWhatsAppException(jid.toSimpleJid().toString(), jid.user()));
    }

    /**
     * Envia e confere o resultado. O Cobalt não falha o future quando desiste de um envio: marca a
     * mensagem com status ERROR e conclui normalmente.
     */
    private MessageInfo<?> deliver(java.util.function.Supplier<CompletableFuture<? extends MessageInfo<?>>> send,
                                   String what) {
        MessageInfo<?> sent = await(send.get(), what);
        if (sent instanceof ChatMessageInfo chat && chat.status() == MessageStatus.ERROR) {
            throw new WhatsAppGateway.GatewayException("Falha no " + what + " (status ERROR no Cobalt)", null);
        }
        return sent;
    }

    /** Monta a resposta do envio e publica o send.message, como o stub. */
    private SentMessage sentMessage(Instance instance, Connection connection, MessageInfo<?> sent, String remoteJid) {
        var timestamp = sent.timestampSeconds().orElse(Instant.now().getEpochSecond());
        var message = new SentMessage(sent.id(), remoteJid, timestamp);
        events.publishEvent(new GatewayEvent.MessageSent(instance.getName(), message));
        // Depois do send.message: os status que chegaram durante o envio saem aqui, já na ordem certa
        connection.statuses.sent(sent.id(), remoteJid);
        return message;
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
