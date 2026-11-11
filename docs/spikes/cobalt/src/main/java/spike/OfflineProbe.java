package spike;

import it.auties.whatsapp.api.ClientType;
import it.auties.whatsapp.api.QrHandler;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.controller.ControllerSerializer;
import it.auties.whatsapp.controller.KeysSpec;
import it.auties.whatsapp.controller.StoreSpec;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Sem rede: gera chaves, serializa/desserializa Keys e Store e monta o cliente (ADR 0007). */
public class OfflineProbe {
    public static void main(String[] args) {
        System.out.println("java " + Runtime.version());
        var id = UUID.randomUUID();

        // 1) Geração de chaves (curve25519 etc.) offline
        var pair = ControllerSerializer.discarding().newStoreKeysPair(id, null, List.of(), ClientType.WEB);
        byte[] keys = KeysSpec.encode(pair.keys());
        byte[] store = StoreSpec.encode(pair.store());
        System.out.printf("keys=%d bytes, store=%d bytes%n", keys.length, store.length);

        // 2) Ida e volta: decode(encode(x)) gera os mesmos bytes
        var keysBack = KeysSpec.encode(KeysSpec.decode(keys));
        var storeBack = StoreSpec.encode(StoreSpec.decode(store));
        System.out.println("roundtrip keys=" + Arrays.equals(keys, keysBack) + " store=" + Arrays.equals(store, storeBack));

        // 3) Monta o cliente sem conectar
        var api = Whatsapp.webBuilder()
                .serializer(ControllerSerializer.discarding())
                .newConnection(id)
                .name("msgforge-probe")
                .unregistered(QrHandler.toString(qr -> System.out.println("QR: " + qr)));
        System.out.println("cliente montado: conectado=" + api.isConnected() + " uuid=" + api.store().uuid());
    }
}
