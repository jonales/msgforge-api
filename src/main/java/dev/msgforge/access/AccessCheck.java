package dev.msgforge.access;

import dev.msgforge.access.AccessRules.Subject;
import dev.msgforge.instance.Instance;
import java.util.UUID;

/**
 * As permissões de uma pessoa lidas uma vez ({@link AccessService#check}), para responder várias
 * perguntas sem voltar ao banco: a lista de instâncias filtra cada linha e o SSE filtra cada
 * inscrito a cada envio. Não guarde por mais que uma requisição ou um envio: é assim que um papel
 * alterado vale na próxima vez.
 */
public final class AccessCheck {

    /** Ninguém: não pode nada (sessão sem usuário, usuário apagado). */
    public static final AccessCheck NOBODY = new AccessCheck(Subject.NOBODY);

    private final Subject subject;

    AccessCheck(Subject subject) {
        this.subject = subject;
    }

    /** Usuário ativo (ou a chave global). Um bloqueado não pode nada. */
    public boolean active() {
        return subject.active();
    }

    public boolean can(Permission permission, Instance instance) {
        return can(permission, instance.getOrganizationId(), instance.getId());
    }

    public boolean can(Permission permission, UUID organizationId, UUID instanceId) {
        return AccessRules.can(subject, permission, organizationId, instanceId);
    }

    /** Tem a permissão em alguma organização (o botão "Nova instância", por exemplo)? */
    public boolean canSomewhere(Permission permission) {
        return AccessRules.canSomewhere(subject, permission);
    }
}
