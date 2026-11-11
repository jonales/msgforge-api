package spike;

import it.auties.whatsapp.api.ErrorHandler;
import it.auties.whatsapp.api.PairingCodeHandler;
import it.auties.whatsapp.api.QrHandler;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.controller.ControllerSerializer;
import it.auties.whatsapp.model.info.ChatMessageInfo;
import it.auties.whatsapp.model.jid.Jid;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Teste AO VIVO do Cobalt 0.0.10 (ADR 0007). Rode na sua máquina, com um NÚMERO DE TESTE
 * (conexão não oficial pode levar a banimento).
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=spike.LiveProbe -Dexec.args="qr 5561999999999"
 * mvn -q compile exec:java -Dexec.mainClass=spike.LiveProbe -Dexec.args="pair:5561988887777 5561999999999"
 * </pre>
 * 1º argumento: "qr" (mostra o QR no terminal) ou "pair:NUMERO" (pairing code do número de teste).
 * 2º argumento (opcional): número que vai receber uma mensagem de teste.
 * A sessão fica em ./cobalt-session (rodar de novo deve reconectar sem QR: teste de sessão).
 */
public class LiveProbe {

    public static void main(String[] args) throws Exception {
        var mode = args.length > 0 ? args[0] : "qr";
        var target = args.length > 1 ? args[1] : null;
        var sessionDir = Path.of("cobalt-session");
        var idFile = sessionDir.resolve("probe-id");
        Files.createDirectories(sessionDir);
        var id = Files.exists(idFile) ? UUID.fromString(Files.readString(idFile).trim()) : UUID.randomUUID();
        Files.writeString(idFile, id.toString());

        var options = Whatsapp.webBuilder()
                .serializer(ControllerSerializer.toProtobuf(sessionDir))
                .newConnection(id)
                .name("Msgforge spike")
                .errorHandler((api, location, error) -> {
                    log("ERRO em " + location + ": " + error);
                    return ErrorHandler.Result.DISCARD;
                });

        var registered = options.registered();
        Whatsapp api;
        if (registered.isPresent()) {
            log("sessão encontrada: reconectando SEM QR");
            api = registered.get();
        } else if (mode.startsWith("pair:")) {
            api = options.unregistered(Long.parseLong(mode.substring(5)), PairingCodeHandler.toTerminal());
        } else {
            api = options.unregistered(QrHandler.toTerminal());
        }

        api.addLoggedInListener(whatsapp -> {
            log("LOGADO como " + whatsapp.store().jid().map(Object::toString).orElse("?"));
            if (target != null) {
                whatsapp.sendMessage(Jid.of(target), "Teste do spike Msgforge/Cobalt " + Instant.now())
                        .thenAccept(sent -> log("ENVIADA id=" + sent.id()))
                        .exceptionally(error -> { log("FALHA AO ENVIAR: " + error); return null; });
            }
        });
        api.addNewChatMessageListener((ChatMessageInfo message) -> log("RECEBIDA de " + message.senderJid()
                + " (" + message.pushName().orElse("") + ") fromMe=" + message.fromMe()
                + " tipo=" + message.message().content().getClass().getSimpleName()));
        api.addMessageStatusListener(info -> log("STATUS " + info.id() + " -> "
                + (info instanceof ChatMessageInfo chat ? chat.status() : info.getClass().getSimpleName())));
        api.addDisconnectedListener(reason -> log("DESCONECTADO: " + reason));

        log("conectando...");
        api.connect().join();
        log("ouvindo por 3 minutos; mande uma mensagem para o número de teste");
        Thread.sleep(Duration.ofMinutes(3));
        api.disconnect().join();
        log("fim");
        System.exit(0);
    }

    private static void log(String text) {
        System.out.println("[" + Instant.now() + "] " + text);
    }
}
