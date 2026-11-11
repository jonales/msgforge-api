package dev.jarzap.instance;

import dev.jarzap.common.ApiExceptionHandler.BadRequestException;
import dev.jarzap.common.ApiExceptionHandler.NotFoundException;
import dev.jarzap.gateway.WhatsAppGateway;
import dev.jarzap.instance.InstanceDtos.ConnectionStateResponse;
import dev.jarzap.instance.InstanceDtos.CreateInstanceRequest;
import dev.jarzap.instance.InstanceDtos.CreateInstanceResponse;
import dev.jarzap.instance.InstanceDtos.FetchedInstance;
import dev.jarzap.instance.InstanceDtos.InstanceInfo;
import dev.jarzap.instance.InstanceDtos.QrCode;
import dev.jarzap.webhook.WebhookService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstanceService {

    private final InstanceRepository instances;
    private final InstanceStateService state;
    private final WebhookService webhooks;
    private final WhatsAppGateway gateway;

    public InstanceService(InstanceRepository instances, InstanceStateService state,
                           WebhookService webhooks, WhatsAppGateway gateway) {
        this.instances = instances;
        this.state = state;
        this.webhooks = webhooks;
        this.gateway = gateway;
    }

    @Transactional
    public CreateInstanceResponse create(CreateInstanceRequest request) {
        if (instances.existsByName(request.instanceName())) {
            throw new BadRequestException("This name \"" + request.instanceName() + "\" is already in use.");
        }
        var token = request.token() == null || request.token().isBlank()
                ? UUID.randomUUID().toString().toUpperCase()
                : request.token();
        var instance = instances.save(new Instance(request.instanceName(), token,
                request.integrationOrDefault(), request.number()));

        if (request.webhook() != null) {
            webhooks.save(instance, request.webhook());
        }

        QrCode qr = null;
        if (Boolean.TRUE.equals(request.qrcode())) {
            qr = connect(instance, request.number());
        }
        var info = new InstanceInfo(instance.getName(), instance.getId().toString(),
                instance.getIntegration(), instance.getConnectionState().value());
        return new CreateInstanceResponse(info, token, qr);
    }

    @Transactional
    public QrCode connect(String instanceName, String number) {
        return connect(get(instanceName), number);
    }

    private QrCode connect(Instance instance, String number) {
        var result = gateway.connect(instance, number);
        instance.setConnectionState(ConnectionState.CONNECTING);
        return new QrCode(result.pairingCode(), result.qrCode(), result.qrBase64(), 1);
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

    @Transactional
    public void logout(String instanceName) {
        var instance = get(instanceName);
        gateway.logout(instance);
        instance.setConnectionState(ConnectionState.CLOSE);
    }

    @Transactional
    public void delete(String instanceName) {
        var instance = get(instanceName);
        if (instance.getConnectionState() != ConnectionState.CLOSE) {
            gateway.logout(instance);
        }
        instances.delete(instance);
        state.evict(instanceName);
    }

    public Instance get(String instanceName) {
        return instances.findByName(instanceName)
                .orElseThrow(() -> new NotFoundException("The \"" + instanceName + "\" instance does not exist"));
    }
}
