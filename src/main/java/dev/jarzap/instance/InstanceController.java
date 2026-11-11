package dev.jarzap.instance;

import dev.jarzap.instance.InstanceDtos.ConnectionStateResponse;
import dev.jarzap.instance.InstanceDtos.CreateInstanceRequest;
import dev.jarzap.instance.InstanceDtos.CreateInstanceResponse;
import dev.jarzap.instance.InstanceDtos.FetchedInstance;
import dev.jarzap.instance.InstanceDtos.QrCode;
import dev.jarzap.instance.InstanceDtos.SimpleResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rotas de /instance no mesmo contrato da Evolution API v2. */
@RestController
@RequestMapping("/instance")
public class InstanceController {

    private final InstanceService service;

    public InstanceController(InstanceService service) {
        this.service = service;
    }

    @PostMapping("/create")
    public ResponseEntity<CreateInstanceResponse> create(@Valid @RequestBody CreateInstanceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/connect/{instance}")
    public QrCode connect(@PathVariable String instance, @RequestParam(required = false) String number) {
        return service.connect(instance, number);
    }

    @GetMapping("/connectionState/{instance}")
    public ConnectionStateResponse connectionState(@PathVariable String instance) {
        return service.connectionState(instance);
    }

    @GetMapping("/fetchInstances")
    public List<FetchedInstance> fetchInstances(@RequestParam(required = false) String instanceName) {
        return service.fetch(instanceName);
    }

    @DeleteMapping("/logout/{instance}")
    public SimpleResponse logout(@PathVariable String instance) {
        service.logout(instance);
        return SimpleResponse.ok("Instance logged out");
    }

    @DeleteMapping("/delete/{instance}")
    public SimpleResponse delete(@PathVariable String instance) {
        service.delete(instance);
        return SimpleResponse.ok("Instance deleted");
    }
}
