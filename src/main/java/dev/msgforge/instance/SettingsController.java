package dev.msgforge.instance;

import dev.msgforge.instance.InstanceService.ProxyFields;
import dev.msgforge.instance.SettingsDtos.Proxy;
import dev.msgforge.instance.SettingsDtos.ProxyRequest;
import dev.msgforge.instance.SettingsDtos.SetProxyResponse;
import dev.msgforge.instance.SettingsDtos.SetSettingsResponse;
import dev.msgforge.instance.SettingsDtos.Settings;
import dev.msgforge.instance.SettingsDtos.SettingsRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas de /settings e /proxy (contrato em docs/COMPATIBILIDADE.md): as configurações da instância
 * depois do create. As configurações valem na hora, também na conexão em curso; o proxy, a partir da
 * próxima conexão.
 */
@RestController
public class SettingsController {

    private final InstanceService service;

    public SettingsController(InstanceService service) {
        this.service = service;
    }

    @PostMapping("/settings/set/{instance}")
    public ResponseEntity<SetSettingsResponse> setSettings(@PathVariable String instance,
                                                           @Valid @RequestBody SettingsRequest body) {
        var current = service.find(instance);
        var settings = new InstanceSettings(body.rejectCall(), InstanceService.msgCall(body.msgCall()),
                body.groupsIgnore(), body.alwaysOnline(), body.readMessages(), body.readStatus());
        // syncFullHistory é sim/não e o Manager tem três opções: false só desfaz o "completo"
        // (FULL → NONE); um "recente" escolhido no Manager continua como está
        HistorySync history = body.syncFullHistory() ? HistorySync.FULL
                : current.getHistorySync() == HistorySync.FULL ? HistorySync.NONE : null;
        var saved = service.updateSettings(instance, settings, history);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SetSettingsResponse(new SetSettingsResponse.Body(saved.getName(), Settings.of(saved))));
    }

    @GetMapping("/settings/find/{instance}")
    public Settings findSettings(@PathVariable String instance) {
        return Settings.of(service.find(instance));
    }

    @PostMapping("/proxy/set/{instance}")
    public ResponseEntity<SetProxyResponse> setProxy(@PathVariable String instance,
                                                     @Valid @RequestBody ProxyRequest body) {
        var fields = new ProxyFields(body.host(), body.port(), body.protocol(), body.username(), body.password());
        var saved = service.updateProxy(instance, body.enabled(), fields, false);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SetProxyResponse(new SetProxyResponse.Body(saved.getName(), Proxy.of(saved))));
    }

    /** Sem proxy: {@code enabled: false} e os campos vazios. A senha nunca sai. */
    @GetMapping("/proxy/find/{instance}")
    public Proxy findProxy(@PathVariable String instance) {
        return Proxy.of(service.find(instance));
    }
}
