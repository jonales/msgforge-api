package dev.msgforge.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Usuário do Manager, identificado pelo e-mail (em minúsculas). A senha fica só como hash do
 * {@code DelegatingPasswordEncoder} ({@code {bcrypt}...}). {@code platformAdmin}: acesso total,
 * acima das organizações, como quem entra com a chave global.
 */
@Entity
@Table(name = "app_user")
public class ManagerUser {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false, length = 12)
    @Enumerated(EnumType.STRING)
    private UserStatus status;

    @Column(name = "platform_admin", nullable = false)
    private boolean platformAdmin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected ManagerUser() {}

    ManagerUser(String email, String passwordHash, boolean platformAdmin) {
        this.id = UUID.randomUUID();
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.status = UserStatus.ATIVO;
        this.platformAdmin = platformAdmin;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    String getPasswordHash() { return passwordHash; }
    public UserStatus getStatus() { return status; }
    public boolean isPlatformAdmin() { return platformAdmin; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    public boolean isActive() {
        return status == UserStatus.ATIVO;
    }

    void setStatus(UserStatus status) {
        this.status = status;
    }

    void loggedIn(Instant when) {
        this.lastLoginAt = when;
    }
}
