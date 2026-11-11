package dev.msgforge.instance;

import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Estado da conexão: Redis como cache rápido, banco como fonte de verdade. */
@Service
public class InstanceStateService {

    private static final String KEY = "msgforge:state:";

    private final InstanceRepository instances;
    private final StringRedisTemplate redis;

    public InstanceStateService(InstanceRepository instances, StringRedisTemplate redis) {
        this.instances = instances;
        this.redis = redis;
    }

    @Transactional
    public void update(String instanceName, ConnectionState state) {
        update(instanceName, state, null, null);
    }

    /** Ao conectar (OPEN), grava também o número e o nome do perfil; nulos não apagam o que já existe. */
    @Transactional
    public void update(String instanceName, ConnectionState state, String ownerJid, String profileName) {
        instances.findByName(instanceName).ifPresent(i -> {
            i.setConnectionState(state);
            if (ownerJid != null) {
                i.setOwnerJid(ownerJid);
            }
            if (profileName != null) {
                i.setProfileName(profileName);
            }
        });
        try {
            redis.opsForValue().set(KEY + instanceName, state.value());
        } catch (Exception ignored) {
            // Redis indisponível não pode derrubar o fluxo; o banco continua correto
        }
    }

    public Optional<ConnectionState> cached(String instanceName) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(KEY + instanceName)).map(ConnectionState::from);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void evict(String instanceName) {
        try {
            redis.delete(KEY + instanceName);
        } catch (Exception ignored) {
        }
    }
}
