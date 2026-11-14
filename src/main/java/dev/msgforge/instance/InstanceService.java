package dev.msgforge.instance;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.common.ApiExceptionHandler.InvalidFieldException;
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
        return create(request, Instance.DEFAULT_ORGANIZATION);
    }

    /**
     * Cria na organização dada (o Manager, ADR 0010). Quem chama já conferiu a permissão de criar
     * nela; a API usa a organização padrão.
     */
    public CreateInstanceResponse create(CreateInstanceRequest request, UUID organizationId) {
        var instance = tx.execute(status -> persist(request, organizationId));
        QrCode qr = null;
        if (Boolean.TRUE.equals(request.qrcode())) {
            qr = connect(instance, request.number());   // depois do commit: eventos do motor enxergam a instância
        }
        var info = new InstanceInfo(instance.getName(), instance.getId().toString(),
                instance.getIntegration(), currentState(instance).value());
        return new CreateInstanceResponse(info, instance.getToken(), qr);
    }

    private Instance persist(CreateInstanceRequest request, UUID organizationId) {
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
        instance.setOrganizationId(organizationId);
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
        return new InstanceSettings(Boolean.TRUE.equals(request.rejectCall()), msgCall(request.msgCall()),
                Boolean.TRUE.equals(request.groupsIgnore()), Boolean.TRUE.equals(request.alwaysOnline()),
                Boolean.TRUE.equals(request.readMessages()), Boolean.TRUE.equals(request.readStatus()));
    }

    /** Texto para quem liga: vazio vira null; acima do teto, 400. */
    public static String msgCall(String value) {
        var msgCall = blankToNull(value);
        if (msgCall != null && msgCall.length() > InstanceSettings.MAX_MSG_CALL_LENGTH) {
            throw new InvalidFieldException("msgCall", "tooLong", "msgCall must have at most "
                    + InstanceSettings.MAX_MSG_CALL_LENGTH + " characters");
        }
        return msgCall;
    }

    /** Proxy do create: os campos têm o prefixo {@code proxy} ({@code proxyHost}...). */
    private InstanceProxy proxy(CreateInstanceRequest request, UUID instanceId) {
        var fields = new ProxyFields(request.proxyHost(), request.proxyPort(), request.proxyProtocol(),
                request.proxyUsername(), request.proxyPassword());
        return validProxy(fields, ProxyFields.CREATE_NAMES, instanceId, false, null);
    }

    /**
     * Campos do proxy como chegam no pedido (texto). {@code names} traz o nome de cada campo no pedido,
     * para as mensagens de erro: o create usa {@code proxyHost}, o {@code /proxy/set} usa {@code host}.
     */
    public record ProxyFields(String host, String port, String protocol, String username, String password) {

        static final ProxyFields CREATE_NAMES =
                new ProxyFields("proxyHost", "proxyPort", "proxyProtocol", "proxyUsername", "proxyPassword");
        static final ProxyFields SET_NAMES = new ProxyFields("host", "port", "protocol", "username", "password");

        /** A senha nunca vai para log nem mensagem de erro. */
        @Override
        public String toString() {
            return "ProxyFields[" + protocol + "://" + host + ":" + port + ", username=" + username
                    + ", password=" + (password == null ? null : "***") + "]";
        }
    }

    /**
     * Valida e monta o proxy: host, porta e protocolo vêm juntos (ou nenhum, e aí não há proxy); usuário
     * e senha são opcionais. Vazio conta como ausente. A senha sai daqui cifrada.
     *
     * @param required     sem host, porta e protocolo, 400 (em vez de "sem proxy")
     * @param keptPassword senha já cifrada a manter quando o pedido não traz senha (null = nenhuma)
     */
    private InstanceProxy validProxy(ProxyFields in, ProxyFields names, UUID instanceId, boolean required,
                                     String keptPassword) {
        var host = blankToNull(in.host());
        var port = blankToNull(in.port());
        var protocol = blankToNull(in.protocol());
        var username = blankToNull(in.username());
        var password = in.password() == null || in.password().isEmpty() ? null : in.password();
        if (host == null && port == null && protocol == null) {
            if (required) {
                throw new InvalidFieldException("host", "required", names.host() + ", " + names.port() + " and "
                        + names.protocol() + " are required when enabled is true");
            }
            if (username != null || password != null) {
                throw new InvalidFieldException("host", "required", names.username() + " and " + names.password()
                        + " need " + names.host() + ", " + names.port() + " and " + names.protocol());
            }
            return null;
        }
        if (host == null || port == null || protocol == null) {
            var missing = host == null ? "host" : port == null ? "port" : "protocol";
            throw new InvalidFieldException(missing, "required", names.host() + ", " + names.port() + " and "
                    + names.protocol() + " must be informed together");
        }
        var normalized = InstanceProxy.normalizeProtocol(protocol);
        if (normalized == null) {
            throw new InvalidFieldException("protocol", "invalid", names.protocol()
                    + " must be one of http, https, socks4, socks4a, socks5, socks5h");
        }
        if (!InstanceProxy.validHost(host)) {
            throw new InvalidFieldException("host", "invalid", names.host() + " is invalid");
        }
        var portNumber = InstanceProxy.parsePort(port);
        if (portNumber == null) {
            throw new InvalidFieldException("port", "invalid", names.port() + " must be a number between 1 and 65535");
        }
        if (username != null && username.contains(":")) {
            throw new InvalidFieldException("username", "invalid", names.username() + " cannot contain \":\"");
        }
        if (password != null && username == null) {
            throw new InvalidFieldException("username", "required", names.password() + " needs " + names.username());
        }
        if (password != null && !secrets.enabled()) {
            throw new InvalidFieldException("password", "noKey", names.password()
                    + " needs SECRETS_ENCRYPTION_KEY configured on the server");
        }
        var encrypted = password == null ? (username == null ? null : keptPassword)
                : secrets.encrypt(instanceId, password);
        return new InstanceProxy(normalized, host, portNumber, username, encrypted);
    }

    // ------------------------------------------------------------ configurações depois do create

    /**
     * Troca as configurações (rejectCall, groupsIgnore...) e, se {@code historySync} não for null, o
     * histórico do próximo pareamento. A conexão em curso passa a usar as novas na hora, sem reconectar.
     */
    public Instance updateSettings(String instanceName, InstanceSettings settings, HistorySync historySync) {
        var instance = tx.execute(status -> {
            var current = get(instanceName);
            current.setSettings(settings);
            if (historySync != null) {
                current.setHistorySync(historySync);
            }
            return instances.save(current);
        });
        gateway.settingsChanged(instance);   // depois do commit, fora da transação
        return instance;
    }

    /**
     * Troca o proxy da instância; {@code enabled = false} remove. Vale a partir da próxima conexão: a
     * conexão em curso continua pelo caminho antigo até reconectar.
     *
     * @param keepPasswordWhenBlank sem senha no pedido, mantém a guardada se o usuário for o mesmo
     *                              (Manager, onde o campo de senha nunca volta preenchido). A API
     *                              grava o que veio: sem senha, sem senha.
     */
    public Instance updateProxy(String instanceName, boolean enabled, ProxyFields fields,
                                boolean keepPasswordWhenBlank) {
        return tx.execute(status -> {
            var instance = get(instanceName);
            InstanceProxy proxy = null;
            if (enabled) {
                var kept = keepPasswordWhenBlank ? instance.getProxy()
                        .filter(old -> old.username() != null && old.username().equals(blankToNull(fields.username())))
                        .map(InstanceProxy::passwordEncrypted).orElse(null) : null;
                proxy = validProxy(fields, ProxyFields.SET_NAMES, instance.getId(), true, kept);
            }
            instance.setProxy(proxy);
            return instances.save(instance);
        });
    }

    /** Dá para guardar senha de proxy? (precisa de {@code SECRETS_ENCRYPTION_KEY}) */
    public boolean secretsEnabled() {
        return secrets.enabled();
    }

    /** Leitura para o {@code /settings/find} e o {@code /proxy/find}. */
    @Transactional(readOnly = true)
    public Instance find(String instanceName) {
        return get(instanceName);
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
