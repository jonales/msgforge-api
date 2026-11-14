package dev.msgforge.access;

import java.io.Serializable;
import java.util.UUID;

/**
 * Quem está logado no Manager, guardado na sessão. Só a identidade: as permissões são lidas do
 * banco a cada pergunta ({@link AccessService#can}), para um papel alterado ou um usuário bloqueado
 * valer já na próxima requisição.
 *
 * @param userId null quando entrou com a chave global (admin da plataforma, ADR 0010)
 * @param email  null quando entrou com a chave global
 */
public record ManagerPrincipal(UUID userId, String email) implements Serializable {

    public static final ManagerPrincipal GLOBAL_KEY = new ManagerPrincipal(null, null);

    public boolean globalKey() {
        return userId == null;
    }

    /** O Spring Security usa o {@code toString} como nome do usuário em logs e eventos. */
    @Override
    public String toString() {
        return globalKey() ? "chave-global" : email;
    }
}
