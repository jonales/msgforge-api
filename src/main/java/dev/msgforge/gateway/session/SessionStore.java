package dev.msgforge.gateway.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistência da sessão do WhatsApp por instância. O conteúdo é do gateway
 * (ex.: o Cobalt serializa as chaves Signal); para o resto da API é só um blob.
 * Apagar a instância apaga a sessão (FK ON DELETE CASCADE).
 */
@Service
public class SessionStore {

    private final StoredSessionRepository sessions;

    public SessionStore(StoredSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> load(UUID instanceId) {
        return sessions.findById(instanceId).map(StoredSession::getPayload);
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID instanceId) {
        return sessions.existsById(instanceId);
    }

    @Transactional
    public void save(UUID instanceId, byte[] payload) {
        sessions.findById(instanceId).ifPresentOrElse(
                s -> s.replace(payload),
                () -> sessions.save(new StoredSession(instanceId, payload)));
    }

    @Transactional
    public void delete(UUID instanceId) {
        sessions.deleteById(instanceId);
    }
}
