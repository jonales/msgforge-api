package dev.msgforge.access;

import dev.msgforge.access.AccessRules.Subject;
import dev.msgforge.instance.Instance;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quem pode o quê no Manager (ADR 0010). {@link #can(ManagerPrincipal, Permission, Instance)} é a
 * pergunta única "este usuário pode X na instância Y?": as telas e os streams vão passar a filtrar
 * por ela (fatia 5). Também faz o login por e-mail e senha, e decide se a chave global entra.
 */
@Service
public class AccessService {

    private final ManagerUserRepository users;
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final PasswordEncoder passwords;
    private final boolean keyLoginAlways;
    /** Comparado quando o e-mail não existe, para o tempo de resposta não revelar quem tem conta. */
    private final String dummyHash;

    public AccessService(ManagerUserRepository users, MembershipRepository memberships,
                         OrganizationRepository organizations, PasswordEncoder passwords,
                         @Value("${msgforge.manager.key-login:true}") boolean keyLoginAlways) {
        this.users = users;
        this.memberships = memberships;
        this.organizations = organizations;
        this.passwords = passwords;
        this.keyLoginAlways = keyLoginAlways;
        this.dummyHash = passwords.encode("msgforge-senha-que-ninguem-tem");
    }

    // ------------------------------------------------------------ autorização

    /** Pode {@code permission} na instância (respeita a organização dela e o escopo do vínculo)? */
    @Transactional(readOnly = true)
    public boolean can(ManagerPrincipal who, Permission permission, Instance where) {
        return AccessRules.can(subject(who), permission, where.getOrganizationId(), where.getId());
    }

    /** Pode {@code permission} na organização como um todo (usuários, papéis)? */
    @Transactional(readOnly = true)
    public boolean canInOrganization(ManagerPrincipal who, Permission permission, UUID organizationId) {
        return AccessRules.can(subject(who), permission, organizationId, null);
    }

    /** Organizações em que a pessoa tem {@code permission}, em ordem de nome. */
    @Transactional(readOnly = true)
    public List<Organization> organizationsWhere(ManagerPrincipal who, Permission permission) {
        var subject = subject(who);
        return organizations.findAllByOrderByNameAsc().stream()
                .filter(org -> AccessRules.can(subject, permission, org.getId(), null))
                .toList();
    }

    @Transactional(readOnly = true)
    public Set<Permission> permissionsIn(ManagerPrincipal who, UUID organizationId) {
        return AccessRules.permissionsIn(subject(who), organizationId);
    }

    /** A sessão continua valendo? Um usuário bloqueado (ou apagado) sai na próxima requisição. */
    @Transactional(readOnly = true)
    public boolean isActive(ManagerPrincipal who) {
        return who.globalKey() || users.findById(who.userId()).map(ManagerUser::isActive).orElse(false);
    }

    /** Chave global ou usuário administrador da plataforma (acima das organizações). */
    @Transactional(readOnly = true)
    public boolean isPlatformAdmin(ManagerPrincipal who) {
        var subject = subject(who);
        return subject.active() && subject.platformAdmin();
    }

    Subject subject(ManagerPrincipal who) {
        if (who == null) {
            return Subject.NOBODY;
        }
        if (who.globalKey()) {
            return Subject.admin();
        }
        var user = users.findById(who.userId()).orElse(null);
        if (user == null) {
            return Subject.NOBODY;
        }
        var grants = memberships.findByUserId(user.getId()).stream().map(Membership::grant).toList();
        return new Subject(user.isPlatformAdmin(), user.isActive(), grants);
    }

    // ------------------------------------------------------------ login

    public sealed interface LoginResult {
        record Success(ManagerPrincipal principal) implements LoginResult {}
        /** E-mail desconhecido ou senha errada: conta como falha no limitador. */
        record WrongCredentials() implements LoginResult {}
        /** Senha certa, mas o usuário está bloqueado ou ainda é convidado. */
        record Disabled() implements LoginResult {}
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String password) {
        var given = password == null ? "" : password;
        var user = users.findByEmail(ManagerUser.normalizeEmail(email)).orElse(null);
        if (user == null || user.getPasswordHash() == null) {
            passwords.matches(given, dummyHash);
            return new LoginResult.WrongCredentials();
        }
        if (!passwords.matches(given, user.getPasswordHash())) {
            return new LoginResult.WrongCredentials();
        }
        if (!user.isActive()) {
            return new LoginResult.Disabled();
        }
        return new LoginResult.Success(new ManagerPrincipal(user.getId(), user.getEmail()));
    }

    @Transactional
    public void recordLogin(ManagerPrincipal who) {
        if (!who.globalKey()) {
            users.findById(who.userId()).ifPresent(user -> user.loggedIn(Instant.now()));
        }
    }

    /**
     * A chave global entra no Manager? Sempre no primeiro acesso (sem nenhum usuário, para criar o
     * primeiro) e, depois, enquanto {@code MANAGER_KEY_LOGIN} for true (padrão nesta fase; ADR 0010,
     * seção "Fase 2").
     */
    @Transactional(readOnly = true)
    public boolean keyLoginAllowed() {
        return keyLoginAlways || !hasUsers();
    }

    @Transactional(readOnly = true)
    public boolean hasUsers() {
        return users.count() > 0;
    }
}
