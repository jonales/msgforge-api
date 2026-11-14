package dev.msgforge.instance;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.gateway.session.SessionStore;
import dev.msgforge.security.SecretCipher;
import dev.msgforge.instance.InstanceDtos.ConnectionStateResponse;
import dev.msgforge.instance.InstanceDtos.CreateInstanceRequest;
import dev.msgforge.instance.InstanceDtos.CreateInstanceResponse;
import dev.msgforge.instance.InstanceDtos.FetchedInstance;
import dev.msgforge.instance.InstanceDtos.InstanceInfo;
import dev.msgforge.instance.InstanceDtos.QrCode;
import dev.msgforge.webhook.WebhookService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operações de instância. As que falam com o gateway ({@code create} com QR, {@code connect},
 * {@code logout}, {@code delete}) <b>não</b> seguram transação enquanto o motor trabalha: com um
 * motor real, eventos chegam por outras threads e precisam enxergar a instância já commitada
 * (ADR 0004), e uma espera de rede não pode prender uma conexão do pool.
 */
@Service
public class InstanceService {

    /**
     * Tamanho mínimo de um token escolhido pelo cliente. Vale só para tokens novos: os que já
     * estão no banco continuam aceitos. O gerado automaticamente (UUID, 36 caracteres) já cumpre.
     */
    public static final int MIN_TOKEN_LENGTH = 32;

    private final InstanceRepository instances;
    private final InstanceStateService state;
    private final WebhookService webhooks;
    private final WhatsAppGateway gateway;
    private final MsgforgeProperties properties;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final SecretCipher secrets;

    public InstanceService(InstanceRepository instances, InstanceStateService state, WebhookService webhooks,
                           WhatsAppGateway gateway, MsgforgeProperties properties, SessionStore sessions,
                           PlatformTransactionManager transactionManager, SecretCipher secrets) {
        this.secrets = secrets;
        this.sessions = sessions;
        this.tx = new TransactionTemplate(transactionManager);
        this.instances = instances;
        this.state = state;
        this.webhooks = webhooks;
        this.gateway = gateway;
        this.properties = properties;
    }

    public CreateInstanceResponse create(CreateInstanceRequest request) {
        var instance = tx.execute(status -> persist(request));
        QrCode qr = null;
        if (Boolean.TRUE.equals(request.qrcode())) {
            qr = connect(instance, request.number());   // depois do commit: eventos do motor enxergam a instância
        }
        var info = new InstanceInfo(instance.getName(), instance.getId().toString(),
                instance.getIntegration(), currentState(instance).value());
        return new CreateInstanceResponse(info, instance.getToken(), qr);
    }

    private Instance persist(CreateInstanceRequest request) {
        if (instances.existsByName(request.instanceName())) {
            throw new BadRequestException("This name \"" + request.instanceName() + "\" is already in use.");
        }
        var token = request.token() == null || request.token().isBlank()
                ? UUID.randomUUID().toString().toUpperCase()
                : request.token();
        if (token.length() < MIN_TOKEN_LENGTH) {
            throw new BadRequestException("token must have at least " + MIN_TOKEN_LENGTH + " characters");
        }
        if (token.equals(properties.authentication().apiKey()) || instances.existsByToken(token)) {
            throw new BadRequestException("This token is already in use.");
        }
        var instance = new Instance(request.instanceName(), token, request.integrationOrDefault(), request.number());
        instance.setSettings(settings(request));
        instance.setProxy(proxy(request, instance.getId()));
        if (Boolean.TRUE.equals(request.syncFullHistory())) {
            instance.setHistorySync(HistorySync.FULL);   // ausente ou false: o padrão (NONE)
        }
        instance = instances.save(instance);

        if (request.webhook() != null) {
            webhooks.save(instance, request.webhook());
        }
        return instance;
    }

    private static InstanceSettings settings(CreateInstanceRequest request) {
        var msgCall = blankToNull(request.msgCall());
        if (msgCall != null && msgCall.length() > InstanceSettings.MAX_MSG_CALL_LENGTH) {
            throw new BadRequestException("msgCall must have at most " + InstanceSettings.MAX_MSG_CALL_LENGTH
                    + " characters");
        }
        return new InstanceSettings(Boolean.TRUE.equals(request.rejectCall()), msgCall,
                Boolean.TRUE.equals(request.groupsIgnore()), Boolean.TRUE.equals(request.alwaysOnline()),
                Boolean.TRUE.equals(request.readMessages()), Boolean.TRUE.equals(request.readStatus()));
    }

    /**
     * Proxy do contrato: {@code proxyHost}, {@code proxyPort} e {@code proxyProtocol} vêm juntos (ou
     * nenhum); usuário e senha são opcionais. Vazio conta como ausente. A senha sai daqui cifrada.
     */
    private InstanceProxy proxy(CreateInstanceRequest request, UUID instanceId) {
        var host = blankToNull(request.proxyHost());
        var port = blankToNull(request.proxyPort());
        var protocol = blankToNull(request.proxyProtocol());
        var username = blankToNull(request.proxyUsername());
        var password = request.proxyPassword() == null || request.proxyPassword().isEmpty()
                ? null : request.proxyPassword();
        if (host == null && port == null && protocol == null) {
            if (username != null || password != null) {
                throw new BadRequestException("proxyUsername and proxyPassword need proxyHost, proxyPort and proxyProtocol");
            }
            return null;
        }
        if (host == null || port == null || protocol == null) {
            throw new BadRequestException("proxyHost, proxyPort and proxyProtocol must be informed together");
        }
        var normalized = InstanceProxy.normalizeProtocol(protocol);
        if (normalized == null) {
            throw new BadRequestException("proxyProtocol must be one of http, https, socks4, socks4a, socks5, socks5h");
        }
        if (!InstanceProxy.validHost(host)) {
            throw new BadRequestException("proxyHost is invalid");
        }
        var portNumber = InstanceProxy.parsePort(port);
        if (portNumber == null) {
            throw new BadRequestException("proxyPort must be a number between 1 and 65535");
        }
        if (username != null && username.contains(":")) {
            throw new BadRequestException("proxyUsername cannot contain \":\"");
        }
        if (password != null && username == null) {
            throw new BadRequestException("proxyPassword needs proxyUsername");
        }
        if (password != null && !secrets.enabled()) {
            throw new BadRequestException("proxyPassword needs SECRETS_ENCRYPTION_KEY configured on the server");
        }
        var encrypted = password == null ? null : secrets.encrypt(instanceId, password);
        return new InstanceProxy(normalized, host, portNumber, username, encrypted);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public QrCode connect(String instanceName, String number) {
        return connect(get(instanceName), number);
    }

    /**
     * Guarda quanto histórico a instância pede no próximo pareamento (Manager). Não reconecta:
     * uma sessão já conectada continua como está.
     */
    public void setHistorySync(String instanceName, HistorySync historySync) {
        tx.executeWithoutResult(status -> {
            var instance = get(instanceName);
            instance.setHistorySync(historySync);
            instances.save(instance);
        });
    }

    /** O estado (connecting/open) vem dos eventos do gateway, não daqui: um login rápido já pode ter chegado. */
    private QrCode connect(Instance instance, String number) {
        var result = gateway.connect(instance, number);
        return new QrCode(result.pairingCode(), result.qrCode(), result.qrBase64(), 1);
    }

    /** Todas as instâncias, por nome (Manager). */
    @Transactional(readOnly = true)
    public List<Instance> list() {
        return instances.findAll().stream()
                .sorted(java.util.Comparator.comparing(Instance::getName))
                .toList();
    }

    /** Estado atual: o cache do Redis primeiro, o banco depois. */
    public ConnectionState stateOf(Instance instance) {
        return currentState(instance);
    }

    private ConnectionState currentState(Instance instance) {
        return state.cached(instance.getName())
                .orElseGet(() -> instances.findById(instance.getId())
                        .map(Instance::getConnectionState)
                        .orElse(instance.getConnectionState()));
    }

    @Transactional(readOnly = true)
    public ConnectionStateResponse connectionState(String instanceName) {
        var current = state.cached(instanceName).orElseGet(() -> get(instanceName).getConnectionState());
        return new ConnectionStateResponse(new ConnectionStateResponse.StateInfo(instanceName, current));
    }

    @Transactional(readOnly = true)
    public List<FetchedInstance> fetch(String instanceName) {
        var list = instanceName == null ? instances.findAll() : List.of(get(instanceName));
        return list.stream().map(FetchedInstance::of).toList();
    }

    public void logout(String instanceName) {
        var instance = get(instanceName);
        gateway.logout(instance);
        sessions.delete(instance.getId());
        state.update(instanceName, ConnectionState.CLOSE);
    }

    public void delete(String instanceName) {
        var instance = get(instanceName);
        // Sempre: uma conexão com QR pendente pode estar com o estado "close" e sem sessão salva, e
        // continuaria viva (gerando QR para uma instância apagada) se o gateway não fosse avisado
        gateway.logout(instance);
        instances.delete(instance);   // a sessão sai junto (FK ON DELETE CASCADE)
        state.evict(instanceName);
    }

    public Instance get(String instanceName) {
        return instances.findByName(instanceName)
                .orElseThrow(() -> new NotFoundException("The \"" + instanceName + "\" instance does not exist"));
    }
}
