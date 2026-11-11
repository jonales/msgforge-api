package dev.msgforge.gateway.cobalt;

import dev.msgforge.gateway.cobalt.CobaltSessionCodec.Parts;
import dev.msgforge.gateway.session.SessionStore;
import it.auties.whatsapp.api.ClientType;
import it.auties.whatsapp.controller.Controller;
import it.auties.whatsapp.controller.ControllerSerializer;
import it.auties.whatsapp.controller.Keys;
import it.auties.whatsapp.controller.KeysSpec;
import it.auties.whatsapp.controller.Store;
import it.auties.whatsapp.controller.StoreSpec;
import it.auties.whatsapp.model.mobile.PhoneNumber;
import java.util.LinkedList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ControllerSerializer} do Cobalt que grava a sessão no Postgres ({@code session_store}),
 * em vez de arquivos em {@code ~/.cobalt}. O UUID da conexão do Cobalt é o id da instância.
 *
 * <p>Keys e Store são gravadas separadamente pelo Cobalt, mas ficam no mesmo blob
 * ({@link CobaltSessionCodec}). Um lock por instância evita que uma gravação de keys apague
 * a de store, ou vice-versa (ler, modificar e gravar).
 *
 * <p>Só conexões web por UUID: telefone e alias não são usados pelo Msgforge.
 */
final class DatabaseSessionSerializer implements ControllerSerializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSessionSerializer.class);

    private final SessionStore sessions;
    private final Executor executor;
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    DatabaseSessionSerializer(SessionStore sessions, Executor executor) {
        this.sessions = sessions;
        this.executor = executor;
    }

    @Override
    public LinkedList<UUID> listIds(ClientType type) {
        return new LinkedList<>(sessions.instanceIds());
    }

    @Override
    public LinkedList<PhoneNumber> listPhoneNumbers(ClientType type) {
        return new LinkedList<>();
    }

    @Override
    public CompletableFuture<Void> serializeKeys(Keys keys, boolean async) {
        var bytes = KeysSpec.encode(keys);
        return run(async, () -> update(keys.uuid(), parts -> parts.withKeys(bytes)));
    }

    @Override
    public CompletableFuture<Void> serializeStore(Store store, boolean async) {
        var bytes = StoreSpec.encode(store);
        return run(async, () -> update(store.uuid(), parts -> parts.withStore(bytes)));
    }

    @Override
    public Optional<Keys> deserializeKeys(ClientType type, UUID id) {
        return read(id).keysOptional().map(KeysSpec::decode);
    }

    @Override
    public Optional<Keys> deserializeKeys(ClientType type, PhoneNumber phoneNumber) {
        return Optional.empty();
    }

    @Override
    public Optional<Keys> deserializeKeys(ClientType type, String alias) {
        return Optional.empty();
    }

    @Override
    public Optional<Store> deserializeStore(ClientType type, UUID id) {
        return read(id).storeOptional().map(StoreSpec::decode);
    }

    @Override
    public Optional<Store> deserializeStore(ClientType type, PhoneNumber phoneNumber) {
        return Optional.empty();
    }

    @Override
    public Optional<Store> deserializeStore(ClientType type, String alias) {
        return Optional.empty();
    }

    @Override
    public void deleteSession(Controller<?> controller) {
        var id = controller.uuid();
        synchronized (lock(id)) {
            sessions.delete(id);
        }
    }

    private Parts read(UUID id) {
        try {
            return sessions.load(id).map(CobaltSessionCodec::decode).orElse(Parts.EMPTY);
        } catch (IllegalArgumentException e) {
            log.warn("Sessão da instância {} ilegível ({}); será tratada como ausente", id, e.getMessage());
            return Parts.EMPTY;
        }
    }

    private void update(UUID id, UnaryOperator<Parts> change) {
        synchronized (lock(id)) {
            sessions.save(id, CobaltSessionCodec.encode(change.apply(read(id))));
        }
    }

    private Object lock(UUID id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }

    private CompletableFuture<Void> run(boolean async, Runnable task) {
        if (async) {
            return CompletableFuture.runAsync(task, executor);
        }
        try {
            task.run();
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
