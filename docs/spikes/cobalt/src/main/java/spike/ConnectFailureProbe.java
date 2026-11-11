package spike;

import it.auties.whatsapp.api.ErrorHandler;
import it.auties.whatsapp.api.QrHandler;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.controller.ControllerSerializer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Sem acesso ao WhatsApp: mostra como connect() falha (rápido, com IOException, sem travar). */
public class ConnectFailureProbe {
    public static void main(String[] args) throws Exception {
        var api = Whatsapp.webBuilder()
                .serializer(ControllerSerializer.discarding())
                .newConnection(UUID.randomUUID())
                .errorHandler((whatsapp, location, throwable) -> {
                    System.out.println("errorHandler: " + location + " -> " + throwable);
                    return ErrorHandler.Result.DISCARD;
                })
                .unregistered(QrHandler.toString(qr -> System.out.println("QR: " + qr)))
                .addDisconnectedListener(reason -> System.out.println("disconnected: " + reason));
        var start = System.nanoTime();
        try {
            api.connect().get(25, TimeUnit.SECONDS);
            System.out.println("connect retornou (inesperado sem rede)");
        } catch (Exception e) {
            System.out.println("connect falhou em " + (System.nanoTime() - start) / 1_000_000 + " ms: " + e);
        }
        System.exit(0);
    }
}
