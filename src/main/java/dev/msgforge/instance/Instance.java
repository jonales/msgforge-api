package dev.msgforge.instance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "instance")
public class Instance {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private String integration;

    private String number;

    @Column(name = "owner_jid")
    private String ownerJid;

    @Column(name = "profile_name")
    private String profileName;

    @Column(name = "connection_state", nullable = false)
    private String connectionState;

    @Column(name = "history_sync", nullable = false, length = 10)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private HistorySync historySync;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Instance() {}

    public Instance(String name, String token, String integration, String number) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.token = token;
        this.integration = integration;
        this.number = number;
        this.connectionState = ConnectionState.CLOSE.value();
        this.historySync = HistorySync.NONE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getToken() { return token; }
    public String getIntegration() { return integration; }
    public String getNumber() { return number; }
    public String getOwnerJid() { return ownerJid; }
    public String getProfileName() { return profileName; }
    public ConnectionState getConnectionState() { return ConnectionState.from(connectionState); }
    public HistorySync getHistorySync() { return historySync; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setConnectionState(ConnectionState state) { this.connectionState = state.value(); }
    public void setOwnerJid(String ownerJid) { this.ownerJid = ownerJid; }
    public void setProfileName(String profileName) { this.profileName = profileName; }
    public void setHistorySync(HistorySync historySync) {
        this.historySync = java.util.Objects.requireNonNull(historySync, "historySync");
    }
}
