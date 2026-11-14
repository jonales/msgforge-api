package dev.msgforge.access;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Vínculo de um usuário com uma organização, com um papel. Vale para a organização inteira
 * ({@code allInstances}) ou só para as instâncias de {@code membership_instance}.
 */
@Entity
@Table(name = "membership")
public class Membership {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private ManagerUser user;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "all_instances", nullable = false)
    private boolean allInstances;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "membership_instance", joinColumns = @JoinColumn(name = "membership_id"))
    @Column(name = "instance_id", nullable = false)
    private Set<UUID> instanceIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Membership() {}

    Membership(ManagerUser user, Role role) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.organizationId = role.getOrganizationId();
        this.role = role;
        this.allInstances = true;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public ManagerUser getUser() { return user; }
    public UUID getOrganizationId() { return organizationId; }
    public Role getRole() { return role; }
    public boolean isAllInstances() { return allInstances; }
    public Set<UUID> getInstanceIds() { return Set.copyOf(instanceIds); }
    public Instant getCreatedAt() { return createdAt; }

    void changeRole(Role role) {
        this.role = role;
    }

    /** Escopo por instância: vazio = organização inteira. A tela do escopo é de uma fatia futura. */
    void limitTo(Set<UUID> instances) {
        this.instanceIds.clear();
        this.instanceIds.addAll(instances);
        this.allInstances = instances.isEmpty();
    }

    AccessRules.Grant grant() {
        return new AccessRules.Grant(organizationId, role.getPermissions(), allInstances, getInstanceIds());
    }
}
