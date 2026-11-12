package dev.msgforge.gateway.cobalt;

import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.model.jid.Jid;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acesso ao cache "este número tem WhatsApp?" do Cobalt ({@code Whatsapp.usersCache}, estático).
 *
 * <p>No Cobalt 0.0.10 esse cache envenena: a consulta guarda o resultado sob o JID devolvido pelo
 * servidor e marca como {@code false} o JID consultado quando os dois diferem (no Brasil, o número
 * com o 9 extra volta sem ele). Daí em diante, envios para o JID consultado são descartados em
 * silêncio (status ERROR, nada vai para o servidor). Tirar o número do cache antes de cada consulta
 * evita o resultado velho.
 */
final class UsersCache {

    private static final Logger log = LoggerFactory.getLogger(UsersCache.class);
    private static final Field FIELD;

    static {
        Field field = null;
        try {
            field = Whatsapp.class.getDeclaredField("usersCache");
            field.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("[cobalt] cache de usuários do Cobalt inacessível ({}); envios repetidos podem falhar", e.toString());
        }
        FIELD = field;
    }

    private UsersCache() {}

    /** Esquece tudo o que o cache sabe sobre o usuário do JID (em qualquer forma de JID). */
    static void forget(Jid jid) {
        cache().ifPresent(cache -> cache.keySet().removeIf(cached -> cached.user() != null
                && cached.user().equals(jid.user())));
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<Jid, Boolean>> cache() {
        if (FIELD == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable((Map<Jid, Boolean>) FIELD.get(null));
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
    }
}
