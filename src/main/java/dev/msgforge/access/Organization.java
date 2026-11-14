package dev.msgforge.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** O cliente do gestor (ADR 0010): tem instâncias, usuários e os próprios papéis. */
@Entity
@Table(name = "organization")
public class Organization {

    /** Criada pela migration; recebe as instâncias antigas e as criadas pela API. */
    public static final UUID DEFAULT_ID = dev.msgforge.instance.Instance.DEFAULT_ORGANIZATION;

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Organization() {}

    Organization(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}
