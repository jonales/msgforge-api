package dev.msgforge.spike;

import com.github.auties00.cobalt.client.WhatsAppClient;
import com.github.auties00.cobalt.client.linked.LinkedWhatsAppClient;
import com.github.auties00.cobalt.client.linked.LinkedWhatsAppClientBuilder;
import com.github.auties00.cobalt.client.linked.LinkedWhatsAppClientVerificationHandler.Web.QrCode;
import com.github.auties00.cobalt.store.linked.LinkedWhatsAppStoreFactory;
import com.github.auties00.cobalt.wire.core.jid.Jid;
import com.github.auties00.cobalt.wire.core.message.MessageInfo;
import com.github.auties00.cobalt.wire.linked.message.LinkedMessageContainer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Teste do Cobalt 0.1.0 com um número de teste. Responde às perguntas que decidem a migração:
 * o aviso de código de segurança some? a sessão volta sem QR? status e LID funcionam sozinhos?
 *
 * <p>Uso: {@code mvn -q compile exec:java [-Dexec.args=pasta-da-sessao]}. Comandos no terminal:
 * {@code existe <número>}, {@code enviar <número> <texto>}, {@code sair}.
 */
public final class TesteCobalt {

    private TesteCobalt() {}

    public static void main(String[] args) throws Exception {
        var sessao = Path.of(args.length > 0 ? args[0] : "sessao").toAbsolutePath();
        log("sessão em " + sessao);
        var web = WhatsAppClient.builder().linkedApi().webClient(LinkedWhatsAppStoreFactory.persistent(sessao));

        LinkedWhatsAppClient client = null;
        var salva = web.loadLatestConnection();
        if (salva.isPresent() && salva.get() instanceof LinkedWhatsAppClientBuilder.Options.Web opcoes) {
            client = opcoes.registered().orElse(null);
            log(client != null ? "sessão salva encontrada: entrando sem QR" : "sessão salva sem registro: vai pedir QR");
        }
        if (client == null) {
            client = web.createConnection()
                    .name("Msgforge teste 0.1")
                    .unregistered(QrCode.toTerminal());
            log("escaneie o QR: WhatsApp > Aparelhos conectados > Conectar um aparelho");
        }

        client.addLoggedInListener(api -> {
            var conta = api.store().accountStore();
            log("LOGADO: jid=" + conta.jid().orElse(null) + " lid=" + conta.lid().orElse(null)
                    + " nome=" + conta.name().orElse(null));
        });
        client.addNewMessageListener((api, info) -> log("MENSAGEM " + descrever(api, info)));
        client.addMessageStatusListener((api, info) -> log("STATUS " + info.key().id().orElse("?") + " -> "
                + info.status().map(Object::toString).orElse("?")));
        client.addDisconnectedListener((api, motivo) -> log("DESCONECTADO: " + motivo));

        client.connect();
        comandos(client);
    }

    /** Lê comandos do terminal até "sair". */
    private static void comandos(LinkedWhatsAppClient client) throws Exception {
        var entrada = new BufferedReader(new InputStreamReader(System.in));
        String linha;
        while ((linha = entrada.readLine()) != null) {
            var partes = linha.strip().split("\\s+", 3);
            try {
                switch (partes[0]) {
                    case "existe" -> log("existe " + partes[1] + ": " + client.hasWhatsapp(List.of(Jid.of(partes[1]))));
                    case "enviar" -> enviar(client, partes[1], partes[2]);
                    case "sair" -> {
                        client.disconnect();
                        log("desconectado; a sessão continua salva");
                        return;
                    }
                    case "" -> {}
                    default -> log("comandos: existe <número> | enviar <número> <texto> | sair");
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                log("faltou argumento; comandos: existe <número> | enviar <número> <texto> | sair");
            } catch (RuntimeException e) {
                log("ERRO: " + e);
            }
        }
    }

    /** Envia pelo JID que o WhatsApp devolve (no Brasil, o número com o 9 pode voltar sem ele). */
    private static void enviar(LinkedWhatsAppClient client, String numero, String texto) {
        var consultado = Jid.of(numero);
        var resposta = client.hasWhatsapp(List.of(consultado));
        log("consulta " + numero + ": " + resposta);
        var destino = resposta.entrySet().stream()
                .filter(e -> Boolean.TRUE.equals(e.getValue()))
                .map(java.util.Map.Entry::getKey)
                .findFirst();
        if (destino.isEmpty()) {
            log("o número não tem WhatsApp: nada enviado");
            return;
        }
        var chave = client.sendMessage(destino.get(), LinkedMessageContainer.of(texto));
        log("ENVIADA id=" + chave.id().orElse("?") + " para " + destino.get());
    }

    private static String descrever(LinkedWhatsAppClient api, MessageInfo info) {
        var chave = info.key();
        var remetente = chave.senderJid().orElse(null);
        var numero = remetente == null ? null
                : api.store().contactStore().findPhoneByLid(remetente).orElse(null);
        return "id=" + chave.id().orElse("?")
                + " conversa=" + chave.parentJid().orElse(null)
                + " de=" + remetente + (numero != null ? " (número " + numero + ")" : "")
                + " minha=" + chave.fromMe()
                + " conteúdo=" + info.message();
    }

    private static void log(String texto) {
        System.out.println("[" + LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + "] " + texto);
    }
}
