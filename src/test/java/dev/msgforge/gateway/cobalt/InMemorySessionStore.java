package dev.msgforge.gateway.cobalt;

import dev.msgforge.gateway.session.SessionStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** SessionStore sem banco, para testes unitários do gateway. */
final class InMemorySessionStore extends SessionStore {

    final Map<UUID, byte[]> data = new ConcurrentHashMap<>();

    /** Atraso artificial no load, para abrir a janela de corrida entre ler e gravar nos testes. */
    volatile long loadDelayMillis;

    InMemorySessionStore() {
        super(null);
    }

    @Override
    public Optional<byte[]> load(UUID instanceId) {
        if (loadDelayMillis > 0) {
            try {
                Thread.sleep(loadDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return Optional.ofNullable(data.get(instanceId));
    }

    @Override
    public boolean exists(UUID instanceId) {
        return data.containsKey(instanceId);
    }

    @Override
    public void save(UUID instanceId, byte[] payload) {
        data.put(instanceId, payload);
    }

    @Override
    public List<UUID> instanceIds() {
        return List.copyOf(data.keySet());
    }

    @Override
    public void delete(UUID instanceId) {
        data.remove(instanceId);
    }
}
