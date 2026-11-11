package dev.msgforge.gateway.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Linha da tabela session_store: credenciais/chaves do WhatsApp de uma instância, opacas para a API. */
@Entity
@Table(name = "session_store")
public class StoredSession {

    @Id
    @Column(name = "instance_id")
    private UUID instanceId;

    @Column(nullable = false)
    private byte[] payload;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoredSession() {}

    public StoredSession(UUID instanceId, byte[] payload) {
        this.instanceId = instanceId;
        replace(payload);
    }

    public UUID getInstanceId() { return instanceId; }
    public byte[] getPayload() { return payload; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void replace(byte[] payload) {
        this.payload = payload;
        this.updatedAt = Instant.now();
    }
}
