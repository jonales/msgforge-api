package dev.msgforge.access;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Catálogo fixo de permissões do Manager (ADR 0010). Os papéis são dados de cada organização e
 * escolhem permissões daqui. Uma permissão nova exige uma migration que a acrescente ao CHECK de
 * {@code role_permission} e, se for o caso, aos papéis-modelo; os papéis já criados não a recebem
 * automaticamente.
 *
 * <p>O texto de cada permissão está em {@code messages.properties}
 * ({@code manager.permission.<NOME>} e {@code manager.permission.<NOME>.hint}).
 */
public enum Permission {

    INSTANCE_VIEW(Group.INSTANCES),
    /** Conectar, reconectar e ver o QR ou o código de pareamento. */
    INSTANCE_CONNECT(Group.INSTANCES),
    INSTANCE_CONNECT_LINK(Group.INSTANCES),
    INSTANCE_CREATE(Group.INSTANCES),
    INSTANCE_DELETE(Group.INSTANCES),

    CHAT_VIEW(Group.CHATS),
    CHAT_SEND(Group.CHATS),

    /** Eventos e webhook da instância. */
    EVENTS_CONFIGURE(Group.CONFIGURATION),
    /** Comportamento da conta (rejeitar ligações, ignorar grupos, proxy...). */
    ACCOUNT_CONFIGURE(Group.CONFIGURATION),

    /** Ver, criar, bloquear e trocar o papel dos usuários da organização. */
    USERS_MANAGE(Group.PEOPLE),
    ROLES_MANAGE(Group.PEOPLE),
    AUDIT_VIEW(Group.PEOPLE);

    /** Grupos da tela de papéis, na ordem em que aparecem. */
    public enum Group {
        INSTANCES, CHATS, CONFIGURATION, PEOPLE;

        public List<Permission> permissions() {
            return Arrays.stream(Permission.values()).filter(p -> p.group == this).toList();
        }
    }

    private final Group group;

    Permission(Group group) {
        this.group = group;
    }

    public Group group() {
        return group;
    }

    public static Set<Permission> all() {
        return EnumSet.allOf(Permission.class);
    }

    /**
     * Nomes vindos de formulário: ignora vazios e lança {@link IllegalArgumentException} para o que
     * não está no catálogo.
     */
    public static Set<Permission> parse(List<String> names) {
        var result = EnumSet.noneOf(Permission.class);
        if (names != null) {
            for (var name : names) {
                if (name != null && !name.isBlank()) {
                    result.add(Permission.valueOf(name.trim()));
                }
            }
        }
        return result;
    }
}
