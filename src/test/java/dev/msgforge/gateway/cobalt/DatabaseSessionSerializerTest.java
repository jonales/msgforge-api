package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;

import it.auties.whatsapp.api.ClientType;
import it.auties.whatsapp.controller.ControllerSerializer;
import it.auties.whatsapp.controller.KeysSpec;
import it.auties.whatsapp.controller.StoreKeysPair;
import it.auties.whatsapp.controller.StoreSpec;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Usa chaves reais geradas pelo Cobalt (offline). */
class DatabaseSessionSerializerTest {

    private final InMemorySessionStore sessions = new InMemorySessionStore();
    private final DatabaseSessionSerializer serializer =
            new DatabaseSessionSerializer(sessions, Executors.newVirtualThreadPerTaskExecutor());

    private static StoreKeysPair newSession(UUID id) {
        return ControllerSerializer.discarding().newStoreKeysPair(id, null, List.of(), ClientType.WEB);
    }

    @Test
    void persistsKeysAndStoreInOneBlobAndReadsThemBack() {
        var id = UUID.randomUUID();
        var session = newSession(id);

        serializer.serializeKeys(session.keys(), false).join();
        serializer.serializeStore(session.store(), true).join();

        assertThat(sessions.data).containsOnlyKeys(id);
        assertThat(serializer.listIds(ClientType.WEB)).containsExactly(id);
        assertThat(KeysSpec.encode(serializer.deserializeKeys(ClientType.WEB, id).orElseThrow()))
                .isEqualTo(KeysSpec.encode(session.keys()));
        assertThat(StoreSpec.encode(serializer.deserializeStore(ClientType.WEB, id).orElseThrow()))
                .isEqualTo(StoreSpec.encode(session.store()));
    }

    @Test
    void concurrentKeysAndStoreWritesDoNotOverwriteEachOther() {
        // Uma gravação de keys e uma de store ao mesmo tempo, as duas lendo o blob vazio.
        // Sem o lock por instância, a segunda a gravar apaga a parte da primeira.
        sessions.loadDelayMillis = 30;
        for (int round = 0; round < 5; round++) {
            var id = UUID.randomUUID();
            var session = newSession(id);

            CompletableFuture.allOf(
                    serializer.serializeKeys(session.keys(), true),
                    serializer.serializeStore(session.store(), true)).join();

            assertThat(serializer.deserializeKeys(ClientType.WEB, id)).as("keys, rodada %d", round).isPresent();
            assertThat(serializer.deserializeStore(ClientType.WEB, id)).as("store, rodada %d", round).isPresent();
        }
    }

    @Test
    void deleteSessionAndUnreadableBlobsBehaveAsNoSession() {
        var id = UUID.randomUUID();
        var session = newSession(id);
        serializer.serializeKeys(session.keys(), false).join();

        serializer.deleteSession(session.keys());
        assertThat(sessions.data).isEmpty();
        assertThat(serializer.deserializeKeys(ClientType.WEB, id)).isEmpty();

        sessions.save(id, "lixo".getBytes());
        assertThat(serializer.deserializeKeys(ClientType.WEB, id)).isEmpty();
        assertThat(serializer.deserializeStore(ClientType.WEB, id)).isEmpty();
    }

    @Test
    void onlyWebSessionsByUuidAreSupported() {
        assertThat(serializer.listPhoneNumbers(ClientType.WEB)).isEmpty();
        assertThat(serializer.deserializeKeys(ClientType.WEB, "alias")).isEmpty();
    }
}
