package dev.msgforge.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "webhook")
public class Webhook {

    @Id
    private UUID id;

    @Column(name = "instance_id", nullable = false, unique = true)
    private UUID instanceId;

    @Column(nullable = false)
    private String url;

    private boolean enabled;

    @Column(name = "by_events")
    private boolean byEvents;

    private boolean base64;

    /** Lista de EventType separada por vírgula; vazia = todos os eventos. */
    @Column(nullable = false)
    private String events;

    /** Headers extras em JSON. */
    private String headers;

    protected Webhook() {}

    public Webhook(UUID instanceId) {
        this.id = UUID.randomUUID();
        this.instanceId = instanceId;
        this.events = "";
    }

    public UUID getId() { return id; }
    public UUID getInstanceId() { return instanceId; }
    public String getUrl() { return url; }
    public boolean isEnabled() { return enabled; }
    public boolean isByEvents() { return byEvents; }
    public boolean isBase64() { return base64; }
    public String getHeaders() { return headers; }

    public List<String> getEvents() {
        return events.isBlank() ? List.of() : Arrays.asList(events.split(","));
    }

    public void update(String url, boolean enabled, boolean byEvents, boolean base64, List<String> events, String headers) {
        this.url = url;
        this.enabled = enabled;
        this.byEvents = byEvents;
        this.base64 = base64;
        this.events = events == null ? "" : String.join(",", events);
        this.headers = headers;
    }
}
