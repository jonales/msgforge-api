package dev.msgforge.instance;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.gateway.session.SessionStore;
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

    private final InstanceRepository instances;
    private final InstanceStateService state;
    private final WebhookService webhooks;
    private final WhatsAppGateway gateway;
    private final MsgforgeProperties properties;
    private final SessionStore sessions;
    private final TransactionTemplate tx;

    public InstanceService(InstanceRepository instances, InstanceStateService state, WebhookService webhooks,
                           WhatsAppGateway gateway, MsgforgeProperties properties, SessionStore sessions,
                           PlatformTransactionManager transactionManager) {
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
        if (token.equals(properties.authentication().apiKey()) || instances.existsByToken(token)) {
            throw new BadRequestException("This token is already in use.");
        }
        var instance = instances.save(new Instance(request.instanceName(), token,
                request.integrationOrDefault(), request.number()));

        if (request.webhook() != null) {
            webhooks.save(instance, request.webhook());
        }
        return instance;
    }

    public QrCode connect(String instanceName, String number) {
        return connect(get(instanceName), number);
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
        if (currentState(instance) != ConnectionState.CLOSE || sessions.exists(instance.getId())) {
            gateway.logout(instance);
        }
        instances.delete(instance);   // a sessão sai junto (FK ON DELETE CASCADE)
        state.evict(instanceName);
    }

    public Instance get(String instanceName) {
        return instances.findByName(instanceName)
                .orElseThrow(() -> new NotFoundException("The \"" + instanceName + "\" instance does not exist"));
    }
}
