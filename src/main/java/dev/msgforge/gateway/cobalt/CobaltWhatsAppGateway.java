package dev.msgforge.gateway.cobalt;

import com.github.auties00.cobalt.client.WhatsAppClient;
import com.github.auties00.cobalt.client.WhatsAppClientDisconnectReason;
import com.github.auties00.cobalt.client.WhatsAppClientProxy;
import com.github.auties00.cobalt.client.linked.LinkedWhatsAppClient;
import com.github.auties00.cobalt.client.linked.LinkedWhatsAppClientBuilder;
import com.github.auties00.cobalt.client.linked.LinkedWhatsAppClientPasskeyAuthenticator;
import com.github.auties00.cobalt.client.linked.LinkedWhatsAppClientVerificationHandler;
import com.github.auties00.cobalt.client.linked.WhatsAppLinkedClientErrorResult;
import com.github.auties00.cobalt.exception.linked.WhatsAppConnectionException;
import com.github.auties00.cobalt.stanza.model.SizedInputStream;
import com.github.auties00.cobalt.stanza.model.Stanza;
import com.github.auties00.cobalt.stanza.model.StanzaAttribute;
import com.github.auties00.cobalt.store.linked.LinkedWhatsAppStoreFactory;
import com.github.auties00.cobalt.wire.core.jid.Jid;
import com.github.auties00.cobalt.wire.core.jid.JidProvider;
import com.github.auties00.cobalt.wire.core.message.MessageInfo;
import com.github.auties00.cobalt.wire.core.message.MessageKeyBuilder;
import com.github.auties00.cobalt.wire.linked.chat.ChatMessageInfo;
import com.github.auties00.cobalt.wire.linked.chat.ChatPolicy;
import com.github.auties00.cobalt.wire.linked.chat.group.GroupMetadata;
import com.github.auties00.cobalt.wire.linked.call.CallEndReason;
import com.github.auties00.cobalt.wire.linked.call.IncomingCall;
import com.github.auties00.cobalt.wire.linked.chat.group.GroupMetadataEditBuilder;
import com.github.auties00.cobalt.wire.linked.contact.ContactStatus;
import com.github.auties00.cobalt.wire.linked.media.MediaProvider;
import com.github.auties00.cobalt.wire.linked.message.LinkedMessageContainer;
import com.github.auties00.cobalt.wire.linked.message.Message;
import com.github.auties00.cobalt.wire.linked.message.contact.ContactMessage;
import com.github.auties00.cobalt.wire.linked.message.contact.ContactMessageBuilder;
import com.github.auties00.cobalt.wire.linked.message.contact.ContactsArrayMessageBuilder;
import com.github.auties00.cobalt.wire.linked.message.location.LocationMessageBuilder;
import com.github.auties00.cobalt.wire.linked.message.media.AudioMessageBuilder;
import com.github.auties00.cobalt.wire.linked.message.media.DocumentMessageBuilder;
import com.github.auties00.cobalt.wire.linked.message.media.ImageMessageBuilder;
import com.github.auties00.cobalt.wire.linked.message.media.VideoMessageBuilder;
import com.github.auties00.cobalt.wire.linked.message.poll.PollCreationMessage;
import com.github.auties00.cobalt.wire.linked.message.poll.PollCreationMessageBuilder;
import com.github.auties00.cobalt.wire.linked.message.poll.PollCreationMessageOptionBuilder;
import com.github.auties00.cobalt.wire.linked.message.text.ReactionMessageBuilder;
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
import dev.msgforge.instance.InstanceProxy;
import dev.msgforge.instance.InstanceSettings;
import dev.msgforge.security.PrivateNetworkGuard;
import dev.msgforge.security.SecretCipher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Motor real do WhatsApp sobre o Cobalt 0.1.0 de {@code jonales/cobalt-2} (ADR 0007), ativo com
 * {@code MSGFORGE_GATEWAY=cobalt}.
 *
 * <p>Uma conexão do Cobalt por instância. A sessão fica em disco, numa pasta por instância
 * ({@code COBALT_SESSION_DIR/<id da instância>}), no formato do próprio Cobalt; o
 * {@code session_store} guarda só um marcador ({@link #SESSION_MARKER}), que o restaurador de boot e
 * o {@code InstanceService} consultam. Os callbacks do Cobalt viram {@link GatewayEvent}; daí em
 * diante o {@code EventDispatcher} cuida de webhook e RabbitMQ, como com o stub.
 *
 * <p>O Cobalt 0.1.0 tem chamadas bloqueantes (sem {@code CompletableFuture}); elas rodam nas
 * threads virtuais das requisições, e as que podem demorar ganham um teto ({@link #call}).
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

    /** Conteúdo do {@code session_store} para uma sessão em disco (formato 1 = pasta do Cobalt 0.1). */
    static final byte[] SESSION_MARKER = "COBALT-FS1".getBytes(StandardCharsets.US_ASCII);

    private static final ConnectResult NO_CODE = new ConnectResult(null, null, null);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_TYPING_DELAY_MS = 20_000;

    /** Estado de uma instância conectada (ou conectando). */
    private static final class Connection {
        final String instanceName;
        /** Conexão pedida com pairing code (número informado) em vez de QR. */
        final boolean pairing;
        /** Completa com o primeiro QR/pairing code, no login ou na falha. */
        final CompletableFuture<ConnectResult> firstCode = new CompletableFuture<>();
        volatile LinkedWhatsAppClient api;
        /** Último QR/pairing code gerado, para quem chamar connect de novo enquanto espera o scan. */
        volatile ConnectResult lastCode;
        volatile boolean closingOnPurpose;
        /** Logada no WhatsApp (evento de login do Cobalt), não só com o socket aberto. */
        volatile boolean loggedIn;
        /** LID → número: Store do Cobalt e, como reforço, os nós recebidos. */
        volatile LidResolver lids = new LidResolver();
        /** messages.update das mensagens enviadas, a partir dos recibos. */
        volatile SentStatusTracker statuses;
        /** Configurações do create (rejectCall, groupsIgnore...), lidas no connect. */
        final InstanceSettings settings;
        /** Proxy da instância; null = o global ({@code COBALT_PROXY}) ou conexão direta. */
        final WhatsAppClientProxy proxy;

        Connection(String instanceName, boolean pairing, InstanceSettings settings, WhatsAppClientProxy proxy) {
            this.instanceName = instanceName;
            this.pairing = pairing;
            this.settings = settings;
            this.proxy = proxy;
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
    private final PrivateNetworkGuard guard;
    private final AudioTranscoder transcoder;
    private final MsgforgeProperties.Cobalt config;
    private final SecretCipher secrets;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;

    public CobaltWhatsAppGateway(ApplicationEventPublisher events, SessionStore sessions,
                                 PrivateNetworkGuard guard, AudioTranscoder transcoder,
                                 MsgforgeProperties properties, SecretCipher secrets) {
        this.secrets = secrets;
        this.events = events;
        this.sessions = sessions;
        this.guard = guard;
        this.transcoder = transcoder;
        this.config = properties.cobalt();
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

    /** Pasta da sessão do Cobalt desta instância. */
    Path sessionDirectory(UUID id) {
        return Path.of(config.sessionDir()).resolve(id.toString());
    }

    /** Cria o cliente do Cobalt e começa a conectar. Chamado com o lock da instância. */
    private Connection start(Instance instance, String phoneNumberForPairing) {
        var id = instance.getId();
        var name = instance.getName();
        var connection = new Connection(name, phoneNumberForPairing != null, instance.getSettings(),
                instance.getProxy().map(proxy -> proxyOf(id, proxy)).orElse(null));
        var web = WhatsAppClient.builder().linkedApi()
                .webClient(LinkedWhatsAppStoreFactory.persistent(sessionDirectory(id)));

        LinkedWhatsAppClient restored = null;
        if (sessions.exists(id)) {
            restored = loadSaved(web, connection).orElse(null);
            if (restored == null) {
                // Pasta sem sessão registrada (volume perdido, ou sessão do formato antigo): pede QR de novo
                log.warn("[cobalt] {}: sessão salva não encontrada em disco; será preciso conectar de novo", name);
                sessions.delete(id);
            }
        }
        if (restored != null) {
            connection.api = restored;   // sessão salva: reconecta sem QR
        } else {
            // Só a conexão nova recebe o nome do aparelho: na sessão salva, o mesmo campo guarda o nome
            // do perfil (vindo da sincronização), que vai como pushName no login
            var options = history(configure(newConnection(web).name(config.deviceName()), connection),
                    instance.getHistorySync());
            if (phoneNumberForPairing != null) {
                connection.api = options.unregistered(pairingNumber(phoneNumberForPairing),
                        new LinkedWhatsAppClientVerificationHandler.Web.PairingCode() {
                            @Override
                            public void handle(String code) {
                                events.publishEvent(new GatewayEvent.QrCodeUpdated(name, null, code));
                                connection.codeGenerated(new ConnectResult(code, null, null));
                            }

                            @Override
                            public LinkedWhatsAppClientPasskeyAuthenticator passkeyAuthenticator() {
                                return passkeyCheckpoint(name);
                            }
                        });
            } else {
                var count = new AtomicInteger();
                connection.api = options.unregistered(new LinkedWhatsAppClientVerificationHandler.Web.QrCode() {
                    @Override
                    public void handle(String qr) {
                        var base64 = QrImages.toDataUrl(qr);
                        log.info("[cobalt] {}: QR #{} gerado", name, count.incrementAndGet());
                        events.publishEvent(new GatewayEvent.QrCodeUpdated(name, qr, null, base64));
                        connection.codeGenerated(new ConnectResult(null, qr, base64));
                    }

                    @Override
                    public LinkedWhatsAppClientPasskeyAuthenticator passkeyAuthenticator() {
                        return passkeyCheckpoint(name);
                    }
                });
            }
        }

        connection.lids = new LidResolver(lid -> connection.api.store().contactStore().findPhoneByLid(lid));
        registerListeners(instance, connection);
        connections.put(id, connection);
        events.publishEvent(new GatewayEvent.ConnectionUpdated(name, ConnectionState.CONNECTING, null));

        // connect() bloqueia durante o handshake: roda fora do lock e da requisição
        executor.execute(() -> {
            try {
                connection.api.connect();
            } catch (RuntimeException error) {
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

    /**
     * O WhatsApp pode exigir, numa sessão já conectada, um "checkpoint de integridade" que só uma
     * passkey do celular atende. Sem terminal, o link do checkpoint vai para o log: abra-o no celular
     * que tem a passkey (ou desconecte e conecte de novo).
     */
    private static LinkedWhatsAppClientPasskeyAuthenticator passkeyCheckpoint(String name) {
        return LinkedWhatsAppClientPasskeyAuthenticator.toQr(url ->
                log.warn("[cobalt] {}: o WhatsApp pediu a passkey do aparelho; abra no celular: {}", name, url));
    }

    /**
     * Sessão salva na pasta da instância, já configurada; vazio quando não há sessão registrada.
     *
     * <p>Se a sessão existe mas o motor falha ao abri-la, lança {@link GatewayException} e não
     * mexe em nada: tratar isso como "sem sessão" apagaria o marcador e começaria um QR novo na
     * mesma pasta, por cima da sessão boa. Um defeito do motor viraria um novo scan; assim, a
     * próxima versão do motor ainda restaura a sessão.
     */
    private Optional<LinkedWhatsAppClient> loadSaved(LinkedWhatsAppClientBuilder.Client.Web web, Connection connection) {
        try {
            return web.loadLatestConnection()
                    .filter(LinkedWhatsAppClientBuilder.Options.Web.class::isInstance)
                    .map(LinkedWhatsAppClientBuilder.Options.Web.class::cast)
                    .flatMap(options -> configure(options, connection).registered());
        } catch (IOException | RuntimeException e) {
            log.error("[cobalt] {}: sessão salva não pôde ser aberta ({}); a sessão foi mantida, sem QR novo",
                    connection.instanceName, rootMessage(e));
            throw new GatewayException("A sessão salva não pôde ser aberta pelo motor: " + rootMessage(e)
                    + ". Ela foi mantida; faça logout para descartá-la e conectar de novo.", e);
        }
    }

    /**
     * Histórico pedido no pareamento. Só vale para uma sessão nova: o WhatsApp manda o histórico
     * uma vez, quando o aparelho é vinculado.
     */
    private static LinkedWhatsAppClientBuilder.Options.Web history(LinkedWhatsAppClientBuilder.Options.Web options,
                                                                   dev.msgforge.instance.HistorySync historySync) {
        return switch (historySync == null ? dev.msgforge.instance.HistorySync.NONE : historySync) {
            case NONE -> options.discardHistory();
            case RECENT -> options.defaultHistory();
            case FULL -> options.fullHistory();
        };
    }

    private static LinkedWhatsAppClientBuilder.Options.Web newConnection(LinkedWhatsAppClientBuilder.Client.Web web) {
        try {
            return web.createConnection();
        } catch (IOException e) {
            throw new GatewayException("Não foi possível criar a sessão do Cobalt: " + e.getMessage(), e);
        }
    }

    /**
     * Proxy e tratamento de erros, para conexão nova e sessão salva. O nome do aparelho fica de fora:
     * no Cobalt, ele ocupa o mesmo campo do nome do perfil, e sobrescrevê-lo ao restaurar fazia as
     * mensagens saírem com o nome do aparelho e o {@code profileName} vir nulo. Antes do login, uma
     * falha de conexão encerra a tentativa (DISCONNECT), como o connect que falhava na versão anterior: o evento de desconexão
     * publica o CLOSE e libera quem espera o QR. Depois do login, os erros só vão para o log e o
     * Cobalt segue (ele mesmo reconecta quando a rede volta).
     */
    private LinkedWhatsAppClientBuilder.Options.Web configure(LinkedWhatsAppClientBuilder.Options.Web options,
                                                             Connection connection) {
        options.errorHandler((api, error) -> {
            log.warn("[cobalt] {}: erro: {}", connection.instanceName, error.toString());
            return !connection.loggedIn && error instanceof WhatsAppConnectionException
                    ? WhatsAppLinkedClientErrorResult.DISCONNECT
                    : WhatsAppLinkedClientErrorResult.DISCARD;
        });
        if (connection.proxy != null) {
            options.proxy(connection.proxy);   // o da instância vence o global
        } else if (config.proxy() != null && !config.proxy().isBlank()) {
            options.proxy(WhatsAppClientProxy.of(URI.create(config.proxy())));
        }
        return options;
    }

    /**
     * Proxy da instância, com a senha decifrada só aqui. Nenhuma mensagem de erro leva a URI (que
     * teria a senha): só o {@link InstanceProxy#toString()}, que a esconde.
     */
    WhatsAppClientProxy proxyOf(UUID id, InstanceProxy proxy) {
        String password;
        try {
            password = proxy.hasPassword() ? secrets.decrypt(id, proxy.passwordEncrypted()) : null;
        } catch (IllegalStateException e) {
            throw new GatewayException("A senha do proxy da instância não pôde ser decifrada: " + e.getMessage(), null);
        }
        var userInfo = proxy.username() == null ? null
                : password == null ? proxy.username() : proxy.username() + ":" + password;
        try {
            return WhatsAppClientProxy.of(new URI(proxy.protocol(), userInfo, proxy.host(), proxy.port(),
                    null, null, null));
        } catch (URISyntaxException | RuntimeException e) {
            throw new GatewayException("Proxy da instância inválido: " + proxy, null);
        }
    }

    private void registerListeners(Instance instance, Connection connection) {
        var id = instance.getId();
        var name = connection.instanceName;
        var api = connection.api;
        api.addLoggedInListener(whatsapp -> {
            var account = whatsapp.store().accountStore();
            var wuid = account.jid().map(jid -> jid.toUserJid().toString()).orElse(null);
            var profile = account.name().orElse(null);
            // Antes de o servidor informar o nome do perfil, o Store guarda o nome do aparelho
            var profileName = profile == null || profile.equals(config.deviceName()) ? null : profile;
            log.info("[cobalt] {}: conectada como {}", name, wuid);
            account.lid().ifPresent(lid -> account.jid().ifPresent(phone -> connection.lids.learn(lid, phone)));
            sessions.save(id, SESSION_MARKER);
            // Antes do evento: quem reage ao connection.update "open" já pode enviar
            connection.loggedIn = true;
            connection.lastCode = null;   // conectada: não há mais QR para mostrar
            events.publishEvent(new GatewayEvent.ConnectionUpdated(name, ConnectionState.OPEN, REASON_OK,
                    wuid, profileName));
            connection.firstCode.complete(NO_CODE);
        });
        api.addDisconnectedListener((whatsapp, reason) -> onDisconnected(id, connection, reason));
        var statuses = new SentStatusTracker(id,
                data -> events.publishEvent(new GatewayEvent.MessageStatusUpdated(name, data)));
        connection.statuses = statuses;
        // Antes do listener de mensagens: o par LID ↔ número vem no nó, antes de a mensagem ser decodificada
        api.addNodeReceivedListener((whatsapp, node) -> {
            connection.lids.observe(node);
            statuses.received(node);
        });
        api.addNewMessageListener((whatsapp, info) -> {
            if (!(info instanceof ChatMessageInfo message)) {
                return;
            }
            var chat = message.key().parentJid().map(Jid::toString).orElse(null);
            if (!connection.settings.delivers(chat)) {
                // groupsIgnore / readStatus=false: nem messages.upsert, nem histórico
                wire.debug("{}: nova mensagem chat={} ignorada pela configuração", name, chat);
                return;
            }
            try {
                var data = CobaltMessageMapper.upsert(message, id, connection.lids);
                wire.debug("{}: nova mensagem id={} chat={} -> {}", name, message.key().id().orElse(null),
                        message.key().parentJid().orElse(null), data.isPresent() ? "messages.upsert" : "ignorada");
                data.ifPresent(d -> events.publishEvent(new GatewayEvent.MessageReceived(name, d)));
            } catch (RuntimeException e) {
                log.warn("[cobalt] {}: falha ao converter a mensagem {}: {}", name,
                        message.key().id().orElse(null), e.toString());
            }
            if (connection.settings.marksAsRead(chat, message.key().fromMe())) {
                executor.execute(() -> markReadOnArrival(connection, message));
            }
        });
        api.addCallListener((whatsapp, incoming) -> executor.execute(() -> onCall(instance, connection, incoming)));
        api.addMessageStatusListener((whatsapp, info) -> {
            try {
                var id2 = info instanceof ChatMessageInfo chat ? chat.key().id().orElse(null) : null;
                var data = CobaltMessageMapper.statusUpdate(info, id, connection.lids)
                        .filter(d -> id2 != null && statuses.advanceFromCobalt(id2, String.valueOf(d.get("status"))));
                wire.debug("{}: status (Cobalt) id={} -> {}", name, id2,
                        data.isPresent() ? "messages.update" : "ignorado");
                data.ifPresent(d -> events.publishEvent(new GatewayEvent.MessageStatusUpdated(name, d)));
            } catch (RuntimeException e) {
                log.warn("[cobalt] {}: falha ao converter o status: {}", name, e.toString());
            }
        });
        if (wire.isDebugEnabled()) {
            // Árvore de nomes e atributos (ex.: enc{type=pkmsg}); o conteúdo cifrado nunca é registrado
            api.addNodeReceivedListener((whatsapp, node) -> wire.debug("{}: <- {}", name, outline(node)));
            api.addNodeSentListener((whatsapp, node) -> wire.debug("{}: -> {}", name, outline(node)));
        }
    }

    /** readMessages / readStatus: o tique azul sai assim que a mensagem chega. */
    private void markReadOnArrival(Connection connection, ChatMessageInfo message) {
        try {
            call(() -> {
                connection.api.markMessageAsRead(message.key());
                return null;
            }, "leitura automática");
        } catch (RuntimeException e) {
            log.debug("[cobalt] {}: leitura automática: {}", connection.instanceName, rootMessage(e));
        }
    }

    /**
     * Chamada recebida: com {@code rejectCall}, recusa; com {@code msgCall}, responde com o texto (como
     * no contrato, independentes um do outro). Falhas só vão para o log.
     */
    private void onCall(Instance instance, Connection connection, IncomingCall incoming) {
        var settings = connection.settings;
        if (settings.rejectCall()) {
            try {
                call(() -> {
                    connection.api.rejectCall(incoming, CallEndReason.REJECTED);
                    return null;
                }, "recusa da chamada");
                log.info("[cobalt] {}: chamada {} recusada (rejectCall)", connection.instanceName, incoming.callId());
            } catch (RuntimeException e) {
                log.warn("[cobalt] {}: falha ao recusar a chamada {}: {}", connection.instanceName,
                        incoming.callId(), rootMessage(e));
            }
        }
        var reply = settings.callReply();
        if (reply != null && connection.loggedIn) {
            try {
                // Quem ligou pode vir por LID: traduz para o número quando a sessão conhece o par
                var caller = Jid.of(connection.lids.apply(incoming.peer().toUserJid()));
                deliver(instance, connection, caller, LinkedMessageContainer.of(reply), "resposta à chamada");
            } catch (RuntimeException e) {
                log.warn("[cobalt] {}: falha ao responder a chamada {}: {}", connection.instanceName,
                        incoming.callId(), rootMessage(e));
            }
        }
    }

    /** Nó em árvore, só nomes e atributos de texto ou JID (bytes viram {@code <bytes>}). */
    static String outline(Stanza node) {
        var out = new StringBuilder();
        outline(node, out, 0);
        return out.toString();
    }

    private static void outline(Stanza node, StringBuilder out, int depth) {
        out.append(node.description()).append('{');
        var first = true;
        for (var entry : node.attributes().entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(entry.getKey()).append('=').append(switch (entry.getValue()) {
                case StanzaAttribute.TextAttribute text -> text.value();
                case StanzaAttribute.JidAttribute jid -> jid.value().toString();
                default -> "<bytes>";
            });
        }
        out.append('}');
        var children = node.children();
        if (children.isEmpty() || depth >= 6) {
            return;
        }
        out.append(" [");
        var firstChild = true;
        for (var child : children) {
            if (!firstChild) {
                out.append(", ");
            }
            firstChild = false;
            outline(child, out, depth + 1);
        }
        out.append(']');
    }

    private void onDisconnected(UUID id, Connection connection, WhatsAppClientDisconnectReason reason) {
        connection.loggedIn = false;
        if (reason != WhatsAppClientDisconnectReason.RECONNECTING) {
            connection.firstCode.complete(NO_CODE);   // ninguém fica esperando um QR que não virá
        }
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
                deleteSession(id);   // desconectado pelo celular: a sessão não vale mais
                events.publishEvent(new GatewayEvent.ConnectionUpdated(name, ConnectionState.CLOSE, REASON_LOGGED_OUT));
            }
            case BANNED -> {
                connections.remove(id, connection);
                events.publishEvent(new GatewayEvent.ConnectionUpdated(name, ConnectionState.CLOSE, REASON_BANNED));
            }
            default -> {
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
        try {
            if (connection != null && connection.loggedIn) {
                call(() -> {
                    connection.api.logout();
                    return null;
                }, "logout");
            } else if (connection != null) {
                // Nunca logou (QR ou código pendente): não há aparelho para desvincular, só o socket
                // e o laço de QR para encerrar
                close(instance.getId(), connection);
            }
        } catch (RuntimeException e) {
            // O aparelho pode continuar em "Aparelhos conectados" no celular; a limpeza local segue
            log.warn("[cobalt] {}: logout no WhatsApp falhou ({}); a sessão local é apagada assim mesmo",
                    instance.getName(), rootMessage(e));
            close(instance.getId(), connection);
        } finally {
            deleteSession(instance.getId());
            events.publishEvent(new GatewayEvent.ConnectionUpdated(instance.getName(), ConnectionState.CLOSE,
                    REASON_LOGGED_OUT));
        }
    }

    /** Apaga o marcador e a pasta da sessão. */
    private void deleteSession(UUID id) {
        sessions.delete(id);
        var directory = sessionDirectory(id);
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[cobalt] não foi possível apagar a sessão em {}: {}", directory, e.getMessage());
        }
    }

    /** Desliga sem deslogar (shutdown da aplicação): a sessão continua válida para o próximo boot. */
    private void close(UUID id, Connection connection) {
        connection.closingOnPurpose = true;
        connections.remove(id, connection);
        var done = executor.submit(() -> connection.api.disconnect());
        try {
            done.get(5, TimeUnit.SECONDS);
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
        var jid = resolve(connection, text.jid());
        typing(connection, jid, text.delayMs());
        return deliver(instance, connection, jid, LinkedMessageContainer.of(text.text()), "envio de texto");
    }

    @Override
    public SentMessage sendMedia(Instance instance, OutgoingMedia media) {
        var connection = requireConnected(instance);
        var bytes = loadMedia(media.media());
        if ("video".equals(media.mediaType()) && !MediaSignatures.isVideo(bytes)) {
            throw new BadRequestException("media is not a supported video (MP4, 3GP or WebM)");
        }
        var jid = resolve(connection, media.jid());
        var message = buildMedia(media);
        upload(connection, (MediaProvider) message, bytes);
        typing(connection, jid, media.delayMs());
        return deliver(instance, connection, jid, LinkedMessageContainer.of(message), "envio de mídia");
    }

    @Override
    public SentMessage send(Instance instance, Outgoing message) {
        var connection = requireConnected(instance);
        var audio = message instanceof VoiceNote voice ? voiceAudio(voice) : null;
        var jid = resolve(connection, message.jid());
        var built = buildMessage(message);
        if (audio != null) {
            upload(connection, (MediaProvider) built, audio);
        }
        typing(connection, jid, message.delayMs());
        return deliver(instance, connection, jid, LinkedMessageContainer.of(built), "envio");
    }

    /** Envia a mídia para o CDN do WhatsApp; o Cobalt preenche chaves, hashes e URL na própria mensagem. */
    private void upload(Connection connection, MediaProvider message, byte[] bytes) {
        call(() -> connection.api.uploadMedia(message, new ByteArrayInputStream(bytes)), "envio da mídia ao WhatsApp");
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

    /** Monta a mensagem do Cobalt (a mídia de voz é enviada ao CDN depois, por {@link #upload}). */
    static Message buildMessage(Outgoing message) {
        return switch (message) {
            // Mensagem de voz: o WhatsApp espera OGG/Opus; outros formatos são enviados como estão
            case VoiceNote _ -> new AudioMessageBuilder().mimetype("audio/ogg; codecs=opus").ptt(true).build();
            case Location location -> new LocationMessageBuilder()
                    .degreesLatitude(location.latitude()).degreesLongitude(location.longitude())
                    .name(location.name()).address(location.address()).build();
            case Contacts contacts when contacts.contacts().size() == 1 -> contact(contacts.contacts().getFirst());
            case Contacts contacts -> new ContactsArrayMessageBuilder()
                    .displayName(contacts.contacts().size() + " contacts")
                    .contacts(contacts.contacts().stream().map(CobaltWhatsAppGateway::contact).toList())
                    .build();
            case Reaction reaction -> new ReactionMessageBuilder()
                    .key(new MessageKeyBuilder().parentJid(Jid.of(reaction.jid())).fromMe(reaction.fromMe())
                            .id(reaction.messageId()).build())
                    .text(reaction.emoji())
                    .senderTimestampMs(Instant.now())
                    .build();
            case Poll poll -> poll(poll);
        };
    }

    private static ContactMessage contact(ContactCard card) {
        return new ContactMessageBuilder().displayName(card.displayName()).vcard(card.vcard()).build();
    }

    /** Enquete com chave aleatória; {@code selectableCount} 0 = várias escolhas. */
    static PollCreationMessage poll(Poll poll) {
        var options = poll.values().stream()
                .map(value -> new PollCreationMessageOptionBuilder().optionName(value).build())
                .toList();
        var key = new byte[32];
        RANDOM.nextBytes(key);
        return new PollCreationMessageBuilder()
                .encKey(key)
                .name(poll.name())
                .options(options)
                .selectableOptionsCount(poll.selectableCount())
                .build();
    }

    static Message buildMedia(OutgoingMedia media) {
        return switch (media.mediaType()) {
            case "image" -> new ImageMessageBuilder().caption(media.caption()).mimetype(media.mimeType()).build();
            case "video" -> new VideoMessageBuilder().caption(media.caption()).mimetype(media.mimeType()).build();
            case "audio" -> new AudioMessageBuilder().mimetype(media.mimeType()).build();
            case "document" -> new DocumentMessageBuilder()
                    .fileName(media.fileName() != null ? media.fileName() : "file")
                    .title(media.caption()).mimetype(media.mimeType()).build();
            default -> throw new BadRequestException("Unsupported mediatype: " + media.mediaType());
        };
    }

    @Override
    public Map<String, String> checkNumbers(Instance instance, List<String> jids) {
        return resolveMany(requireConnected(instance), jids);
    }

    /** Versão em lote do {@link #resolve}: para cada JID, o que o WhatsApp conhece ou null. Grupos e
     * outros servidores passam como estão. */
    private Map<String, String> resolveMany(Connection connection, List<String> jids) {
        var numbers = jids.stream().filter(jid -> Jid.of(jid).hasUserServer()).distinct().toList();
        var exists = new HashMap<String, Boolean>();
        if (!numbers.isEmpty()) {
            var query = numbers.stream().map(Jid::of).toList();
            var found = call(() -> connection.api.hasWhatsapp(query), "consulta de números");
            found.forEach((jid, has) -> exists.put(connection.lids.apply(jid), has));
        }
        var resolved = resolveNumbers(numbers, exists);
        var result = new LinkedHashMap<String, String>();
        jids.forEach(jid -> result.put(jid, Jid.of(jid).hasUserServer() ? resolved.get(jid) : jid));
        return result;
    }

    /** Participantes de grupo: o JID que o WhatsApp conhece, ou o pedido (o WhatsApp recusa sozinho). */
    private List<Jid> participants(Connection connection, List<String> jids) {
        var known = resolveMany(connection, jids);
        return jids.stream().map(jid -> Jid.of(known.get(jid) != null ? known.get(jid) : jid)).toList();
    }

    /**
     * O WhatsApp responde pelo JID que conhece, que pode ser o celular brasileiro sem (ou com) o
     * nono dígito. Sem o JID consultado na resposta, vale a forma alternativa.
     */
    static Map<String, String> resolveNumbers(List<String> queried, Map<String, Boolean> found) {
        var result = new LinkedHashMap<String, String>();
        for (var jid : queried) {
            String known;
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

    /** Mensagens recebidas: marcadas pela key; se o Cobalt recusar, a conversa inteira é marcada. */
    @Override
    public void markRead(Instance instance, List<MessageKey> keys) {
        var connection = requireConnected(instance);
        var wholeChats = new LinkedHashSet<String>();
        for (var key : keys) {
            if (key.fromMe()) {
                continue;
            }
            var messageKey = new MessageKeyBuilder().parentJid(Jid.of(key.remoteJid())).fromMe(false)
                    .id(key.id()).build();
            try {
                call(() -> {
                    connection.api.markMessageAsRead(messageKey);
                    return null;
                }, "leitura");
            } catch (GatewayException e) {
                wholeChats.add(key.remoteJid());
            }
        }
        for (var chat : wholeChats) {
            call(() -> {
                connection.api.markChatAsRead(Jid.of(chat));
                return null;
            }, "leitura");
        }
    }

    @Override
    public void sendPresence(Instance instance, String jid, Presence presence, Integer delayMs) {
        var connection = requireConnected(instance);
        var target = resolve(connection, jid);
        chatState(connection, target, contactStatus(presence));
        var temporary = presence == Presence.COMPOSING || presence == Presence.RECORDING;
        if (temporary && delayMs != null && delayMs > 0) {
            try {
                Thread.sleep(Math.min(delayMs, MAX_TYPING_DELAY_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            chatState(connection, target, ContactStatus.AVAILABLE);
        }
    }

    private void chatState(Connection connection, Jid target, ContactStatus status) {
        call(() -> {
            connection.api.editChatState(target, status);
            return null;
        }, "presença");
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
        var store = connection.api.store();
        var self = store.accountStore().jid().map(connection.lids::apply).orElse(null);
        var name = jid.equals(self)
                ? store.accountStore().name().orElse(null)
                : store.contactStore().findContactByJid(target)
                        .flatMap(contact -> contact.chosenName().or(contact::fullName))
                        .orElse(null);
        var status = optional(connection, () -> connection.api.queryAbout(target)).orElse(null);
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
        } catch (NumberNotOnWhatsAppException e) {
            return null;
        }
    }

    private String profilePictureUrl(Connection connection, Jid jid) {
        return optional(connection, () -> connection.api.queryPicture(jid)).map(URI::toString).orElse(null);
    }

    /** Foto e recado podem ser escondidos pela privacidade: a falha vira "sem valor", não erro. */
    private <T> Optional<T> optional(Connection connection, Supplier<Optional<T>> query) {
        try {
            return call(query, "consulta de perfil");
        } catch (GatewayException e) {
            log.debug("[cobalt] {}: {}", connection.instanceName, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void updateProfile(Instance instance, ProfileUpdate update) {
        var connection = requireConnected(instance);
        switch (update) {
            case ProfileName name -> call(() -> {
                connection.api.editName(name.name());
                return null;
            }, "alteração do nome");
            case ProfileStatus status -> call(() -> {
                connection.api.editAbout(status.status());
                return null;
            }, "alteração do recado");
            case ProfilePicture picture -> {
                var bytes = loadMedia(picture.picture());
                if (!MediaSignatures.isImage(bytes)) {
                    throw new BadRequestException("picture must be a JPEG or PNG image");
                }
                call(() -> {
                    connection.api.editProfilePicture(SizedInputStream.of(bytes));
                    return null;
                }, "alteração da foto");
            }
        }
    }

    // ---- Grupos

    @Override
    public GroupInfo createGroup(Instance instance, String subject, String description, List<String> participants) {
        var connection = requireConnected(instance);
        var members = participants(connection, participants);
        var created = call(() -> connection.api.createGroup(subject, members), "criação do grupo");
        if (description != null && !description.isBlank()) {
            editGroup(connection, created.jid(), new GroupMetadataEditBuilder().group(created.jid())
                    .description(new com.github.auties00.cobalt.wire.linked.chat.group.GroupDescription.Set(description)),
                    "descrição do grupo");
            return groupInfo(connection, created.jid());
        }
        return groupInfo(created, connection.lids);
    }

    @Override
    public GroupInfo groupInfo(Instance instance, String groupJid) {
        return groupInfo(requireConnected(instance), Jid.of(groupJid));
    }

    private GroupInfo groupInfo(Connection connection, Jid groupJid) {
        var metadata = call(() -> connection.api.queryGroupInfo(groupJid), "consulta do grupo")
                .orElseThrow(() -> new BadRequestException("Group not found: " + groupJid));
        return groupInfo(metadata, connection.lids);
    }

    /** Os grupos de que a conta participa, numa consulta só ao WhatsApp. */
    @Override
    public List<GroupInfo> groups(Instance instance) {
        var connection = requireConnected(instance);
        return call(() -> connection.api.queryParticipatingGroups(true, true), "consulta dos grupos").stream()
                .map(metadata -> groupInfo(metadata, connection.lids))
                .toList();
    }

    /** {@code names}: LID → número ({@link LidResolver}). */
    static GroupInfo groupInfo(GroupMetadata metadata, java.util.function.Function<Jid, String> names) {
        var participants = metadata.participants().stream()
                .map(p -> new GroupParticipant(names.apply(p.userJid()), p.rank().map(rank -> switch (rank) {
                    case FOUNDER -> "superadmin";
                    case ADMIN -> "admin";
                    case MEMBER -> (String) null;
                }).orElse(null)))
                .toList();
        return new GroupInfo(metadata.jid().toString(), metadata.subject(),
                metadata.subjectAuthorJid().map(names).orElse(null),
                metadata.subjectTimestamp().map(Instant::getEpochSecond).orElse(0L),
                metadata.foundationTimestamp().map(Instant::getEpochSecond).orElse(0L),
                metadata.founderJid().map(names).orElse(null),
                metadata.description().orElse(null),
                metadata.announce() == ChatPolicy.ADMINS,
                metadata.restrict() == ChatPolicy.ADMINS,
                participants);
    }

    @Override
    public String groupInviteCode(Instance instance, String groupJid) {
        var connection = requireConnected(instance);
        return call(() -> connection.api.queryGroupInviteCode(Jid.of(groupJid)), "convite do grupo")
                .orElseThrow(() -> new GatewayException("O WhatsApp não devolveu o convite do grupo", null));
    }

    @Override
    public String revokeGroupInvite(Instance instance, String groupJid) {
        var connection = requireConnected(instance);
        return call(() -> connection.api.revokeGroupInviteCode(Jid.of(groupJid)), "revogação do convite");
    }

    @Override
    public String acceptGroupInvite(Instance instance, String inviteCode) {
        var connection = requireConnected(instance);
        var group = call(() -> connection.api.joinGroupViaInvite(inviteCode), "entrada no grupo");
        if (group == null) {
            throw new BadRequestException("Invalid invite code");
        }
        return group.toString();
    }

    @Override
    public List<String> updateParticipants(Instance instance, String groupJid, ParticipantAction action,
                                           List<String> participants) {
        var connection = requireConnected(instance);
        var group = Jid.of(groupJid);
        var members = participants(connection, participants);
        var done = call(() -> switch (action) {
            case ADD -> connection.api.addGroupParticipants(group, members).keySet();
            case REMOVE -> connection.api.removeGroupParticipants(group, members).keySet();
            case PROMOTE -> {
                connection.api.promoteGroupParticipants(group, members);
                yield members;
            }
            case DEMOTE -> {
                connection.api.demoteGroupParticipants(group, members);
                yield members;
            }
        }, "participantes do grupo").stream().map(connection.lids::apply).collect(Collectors.toSet());
        // Devolve nos JIDs pedidos: o WhatsApp responde pelo JID resolvido (sem o 9, ou LID)
        var result = new ArrayList<String>();
        for (var i = 0; i < participants.size(); i++) {
            if (done.contains(connection.lids.apply(members.get(i)))) {
                result.add(participants.get(i));
            }
        }
        return result;
    }

    @Override
    public void updateGroup(Instance instance, String groupJid, GroupUpdate update) {
        var connection = requireConnected(instance);
        var group = Jid.of(groupJid);
        var edit = new GroupMetadataEditBuilder().group(group);
        switch (update) {
            case GroupSubject subject -> editGroup(connection, group, edit.subject(subject.subject()), "assunto do grupo");
            case GroupDescription description -> editGroup(connection, group, edit.description(
                    com.github.auties00.cobalt.wire.linked.chat.group.GroupDescription.of(description.description())),
                    "descrição do grupo");
            case GroupPicture picture -> {
                var bytes = loadMedia(picture.image());
                if (!MediaSignatures.isImage(bytes)) {
                    throw new BadRequestException("image must be a JPEG or PNG image");
                }
                editGroup(connection, group, edit.picture(
                        com.github.auties00.cobalt.wire.linked.chat.group.GroupPicture.of(bytes)), "foto do grupo");
            }
            case GroupSetting setting -> {
                var policy = setting.adminsOnly() ? ChatPolicy.ADMINS : ChatPolicy.ANYONE;
                editGroup(connection, group, setting.setting() == Setting.SEND_MESSAGES
                        ? edit.sendMessagePolicy(policy)
                        : edit.editInfoPolicy(policy), "configuração do grupo");
            }
        }
    }

    private void editGroup(Connection connection, Jid group, GroupMetadataEditBuilder edit, String what) {
        call(() -> connection.api.editGroupMetadata(edit.build()), what);
    }

    @Override
    public void leaveGroup(Instance instance, String groupJid) {
        var connection = requireConnected(instance);
        call(() -> {
            connection.api.leaveGroup(Jid.of(groupJid));
            return null;
        }, "saída do grupo");
    }

    /** {@code delay} da API: mostra "digitando..." por esse tempo antes de enviar. */
    private void typing(Connection connection, Jid jid, Integer delayMs) {
        if (delayMs == null || delayMs <= 0) {
            return;
        }
        try {
            chatState(connection, jid, ContactStatus.COMPOSING);
            Thread.sleep(Math.min(delayMs, MAX_TYPING_DELAY_MS));
            chatState(connection, jid, ContactStatus.AVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
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
            } catch (IOException | IllegalArgumentException e) {
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
        if (!jid.hasUserServer()) {
            return jid;   // grupo, newsletter...: sem consulta
        }
        Map<Jid, Boolean> result = call(() -> connection.api.hasWhatsapp(List.<JidProvider>of(jid)),
                "consulta do número");
        return result.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .map(entry -> entry.getKey().toUserJid())
                .findFirst()
                .orElseThrow(() -> new NumberNotOnWhatsAppException(jid.toUserJid().toString(), jid.user()));
    }

    /** Envia, publica o send.message e libera os status que chegaram durante o envio. */
    private SentMessage deliver(Instance instance, Connection connection, Jid jid, LinkedMessageContainer message,
                                String what) {
        var key = call(() -> connection.api.sendMessage(jid, message), what);
        var id = key.id().orElseThrow(() -> new GatewayException("Falha no " + what + ": mensagem sem id", null));
        var remoteJid = jid.toString();
        var sent = new SentMessage(id, remoteJid, Instant.now().getEpochSecond());
        events.publishEvent(new GatewayEvent.MessageSent(instance.getName(), sent));
        // Depois do send.message: os status que chegaram durante o envio saem aqui, já na ordem certa
        connection.statuses.sent(id, remoteJid);
        return sent;
    }

    /**
     * Chamada bloqueante do Cobalt com o teto {@code COBALT_SEND_TIMEOUT_MS}. Falhas viram
     * {@link GatewayException} (HTTP 500 com a mensagem); {@link BadRequestException} passa direto.
     */
    private <T> T call(Supplier<T> operation, String what) {
        var future = executor.submit(operation::get);
        try {
            return future.get(config.sendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new GatewayException(what + " interrompido", e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new GatewayException(what + ": o WhatsApp não respondeu em " + config.sendTimeoutMs() + " ms", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BadRequestException bad) {
                throw bad;
            }
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
