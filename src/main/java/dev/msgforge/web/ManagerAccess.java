package dev.msgforge.web;

import dev.msgforge.access.AccessCheck;
import dev.msgforge.access.AccessService;
import dev.msgforge.access.Permission;
import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.instance.Instance;
import dev.msgforge.instance.InstanceService;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * A porta das telas de instância do Manager (ADR 0010, fatia 5). Toda rota com {@code {nome}} passa
 * por {@link #require}: instância que a pessoa não vê (de outra organização ou fora do escopo do
 * vínculo) responde igual a uma que não existe (404), para não revelar que existe; vê, mas o papel
 * não inclui a ação, 403 com a tela "Sem acesso". As permissões são lidas do banco a cada chamada.
 */
@Component
public class ManagerAccess {

    private final AccessService access;
    private final InstanceService instances;

    public ManagerAccess(AccessService access, InstanceService instances) {
        this.access = access;
        this.instances = instances;
    }

    /** As permissões de quem fez a requisição (nenhuma fora de uma sessão do Manager). */
    public AccessCheck current() {
        var who = ManagerTopbar.current();
        return who == null ? AccessCheck.NOBODY : access.check(who);
    }

    /**
     * A instância, se a pessoa a vê e tem {@code permission} nela.
     *
     * @throws NotFoundException     não existe, ou existe e a pessoa não a vê
     * @throws AccessDeniedException vê, mas o papel não inclui {@code permission}
     */
    public Instance require(String name, Permission permission) {
        return require(current(), name, permission);
    }

    Instance require(AccessCheck check, String name, Permission permission) {
        var instance = visible(check, name);
        if (!check.can(permission, instance)) {
            throw new AccessDeniedException("Sem a permissão " + permission + " na instância");
        }
        return instance;
    }

    /** A instância, se a pessoa a vê ({@link Permission#INSTANCE_VIEW}); senão, como se não existisse. */
    Instance visible(AccessCheck check, String name) {
        var instance = instances.get(name);   // NotFoundException com a mesma mensagem nos dois casos
        if (!check.can(Permission.INSTANCE_VIEW, instance)) {
            throw new NotFoundException("The \"" + name + "\" instance does not exist");
        }
        return instance;
    }

    /** O que a pessoa pode na instância: o painel esconde as seções que ela não usa. */
    static Set<Permission> on(AccessCheck check, Instance instance) {
        var result = EnumSet.noneOf(Permission.class);
        for (var permission : Permission.values()) {
            if (check.can(permission, instance)) {
                result.add(permission);
            }
        }
        return result;
    }
}
