# 0007: Motor do WhatsApp: Cobalt 0.0.10, condicionado ao teste ao vivo

**Status:** proposta (2026-11). Vira "aceita" quando o teste ao vivo do
`docs/spikes/cobalt/README.md` passar nos critérios 1 a 5. O `CobaltWhatsAppGateway` já foi
construído (por decisão do usuário, sem esperar o teste ao vivo) e está atrás de
`MSGFORGE_GATEWAY=cobalt`; o padrão continua `stub`.

## Contexto
A Fase 2 troca o `StubWhatsAppGateway` por um motor real. O ADR 0001 previu o Cobalt (Java), mas
nunca foi validado. O spike (`docs/spikes/cobalt/`) testou o que dava para testar sem um celular.
O ambiente de nuvem não alcança o WhatsApp: o proxy recusa `web.whatsapp.com`.

## O que o spike verificou

| Item | Resultado |
|---|---|
| Disponibilidade | `com.github.auties00:cobalt:0.0.10` no Maven Central (jul/2025). Licença MIT |
| Java 25 | Bytecode Java 21 (major 65), roda no JDK 25 sem flags |
| Criptografia/chaves | `newStoreKeysPair` gera chaves offline no JDK 25 |
| Persistência | `KeysSpec`/`StoreSpec.encode/decode` ↔ `byte[]`, ida e volta idêntica. Dá para escrever um `ControllerSerializer` sobre o `session_store` (Postgres) |
| Montagem do cliente | `Whatsapp.webBuilder().serializer(..).newConnection(UUID)` ok; o UUID pode ser o id da instância |
| Falha de rede | `connect()` falha em menos de 1 s com `IOException`, sem travar (`ErrorHandler` disponível) |
| **Dependência `aspose-words`** | **Comercial**, fora do Central (repositório da Aspose); o build **quebra** sem ela. Excluída. **Correção posterior:** o spike concluiu que a ausência só degradava, mas, ao construir o gateway, montar *qualquer* mensagem de mídia falhou com `NoClassDefFoundError: com/aspose/words/SaveOptions`, porque a JVM carrega os tipos referenciados por `Medias` ao verificar a classe. Solução: **shim** em `src/main/java/com/aspose/words` (4 classes vazias, sem código da Aspose). Com ele, documento sai com 1 página e sem miniatura |
| `slf4j-nop` | Dependência transitiva que silenciaria o log da aplicação. **Excluída** |
| ffmpeg (`jaffree`) | Duração e miniatura de vídeo/áudio. Sem o binário, duração 0 e montagem em cerca de 5 ms. Com o binário: MP4 real em cerca de 450 ms; **bytes que não são vídeo fazem o `ffprobe` esperar cerca de 10 s** (limitado). A imagem Docker ainda não instala o ffmpeg |
| Proxy | `OptionsBuilder.proxy(URI)` é respeitado: `COBALT_PROXY`. Os testes usam um proxy numa porta fechada para que o `connect` falhe igual em qualquer lugar, inclusive no CI |
| Outras | `fastjson2`, `jackson-core` 2.x (coexiste com o Jackson 3 do Boot), zxing, libphonenumber; cerca de 10 MB no total |
| Qualidade | Bug visto no código: `DocumentMessage` usa `thumbnail != null ? null : ...` (condição invertida) |
| Manutenção | Upstream ativo até jul/2026, mas numa **reescrita 0.1.0 não publicada** (multi-módulo, nativo, Java 25 + `--enable-preview`). A 0.0.10 tem cerca de 16 meses |

**Não verificado (o maior risco):** se o protocolo da 0.0.10 ainda é aceito pelo WhatsApp hoje
(login, envio, recebimento, reconexão), e com que risco de banimento.

## Mapeamento contrato → Cobalt (base do gateway)

| Msgforge (`WhatsAppGateway` / evento) | Cobalt 0.0.10 |
|---|---|
| `connect` com QR | `webBuilder()...newConnection(id).unregistered(QrHandler)` + `connect()` |
| `connect` com pairing code (`?number=`) | `unregistered(long phone, PairingCodeHandler)` |
| Reconectar com sessão salva | `...newConnection(id).registered()` (via `ControllerSerializer` próprio) |
| `logout` | `logout()`; apagar a sessão (`deleteSession`) |
| `sendText` | `sendMessage(Jid.of(...), text)` |
| `sendMedia` | `sendMessage(jid, MessageContainer)` com mensagens de mídia |
| `qrcode.updated` | callback do `QrHandler` |
| `connection.update` (open/close) | `addLoggedInListener` / `addDisconnectedListener(DisconnectReason)` |
| `messages.upsert` | `addNewChatMessageListener(ChatMessageInfo)` → mapear para o formato Baileys |
| `messages.update` (ack) | `addMessageStatusListener` (`ChatMessageInfo.status()`) |
| Estado `close`/banido | `DisconnectReason.LOGGED_OUT` / `BANNED` |

## Decisão (proposta)
1. Usar o **Cobalt 0.0.10**, só no pacote `gateway/` (regra do ADR 0001), com as exclusões de
   `aspose-words` e `slf4j-nop`.
2. **Antes** de escrever o mapeamento completo, rodar o **teste ao vivo** (critérios do README do
   spike) com um número de teste.
3. Se o teste falhar, as alternativas, em ordem de preferência:
   - **(B) Sidecar com Baileys**, atrás do `WhatsAppGateway`:
     o payload de `MESSAGES_UPSERT` sai nativamente no formato que os clientes esperam, e a
     biblioteca é mantida e muito usada. Custo: um processo Node a mais (fere o "um artefato" só
     no motor) e um protocolo local (HTTP/WebSocket) entre os dois.
   - **(C) Cobalt 0.1.0 compilado do fonte**: obrigaria a aplicação inteira a rodar com
     `--enable-preview`, sem versão publicada e com dependências nativas. Só se (B) for vetado.

## Implementação (Fase 2)
Pacote `dev.msgforge.gateway.cobalt`:
- **`CobaltWhatsAppGateway`:** uma conexão por instância. QR em texto e PNG em base64; pairing
  code com `?number=`; reconexão sem QR quando há sessão; logout; `sendText` (com `delay` =
  "digitando...") e `sendMedia` (URL ou base64, com teto de tamanho e `PrivateNetworkGuard`).
- **Eventos:**
  - `connection.update` com `wuid`/`profileName` ao abrir; ao fechar, os códigos do Baileys
    (401 logout, 403 banido, 428 fechada).
  - `messages.upsert` via `CobaltMessageMapper` (formato Baileys).
  - `messages.update` (status `SERVER_ACK`/`DELIVERY_ACK`/`READ`/`PLAYED`).
- **Concorrência:** `connect`/`logout` são serializados por instância. Antes da revisão, 10
  chamadas simultâneas criavam 10 clientes do WhatsApp para a mesma sessão (provado em teste).
  Agora, um connect com QR pendente reaproveita a conexão e devolve o QR atual.
- **`DatabaseSessionSerializer`:** Keys e Store no `session_store`, num blob só
  (`CobaltSessionCodec`), com lock por instância.
- **Mudança nas camadas acima:** `InstanceService` não segura transação enquanto fala com o
  gateway. Os eventos do motor chegam por threads do Cobalt e precisam enxergar a instância
  commitada (ADR 0004).

## Consequências
- O isolamento atrás de `WhatsAppGateway` permite trocar de motor sem tocar na API: (A), (B) e
  (C) afetam só `gateway/`.
- O `session_store` guarda um blob por instância; o Cobalt tem dois (`Keys` e `Store`). A
  implementação precisa decidir entre empacotar os dois num blob só ou criar uma migration com
  uma coluna nova (seguindo `docs/MIGRATIONS.md`).
- Testes do gateway: unitários com o mapeamento a partir de objetos do Cobalt construídos
  offline. O fluxo real só no teste manual ao vivo. O CI não alcança o WhatsApp, e não deve.

## Teste ao vivo (2026-11-12)
Duas instâncias locais (Docker, `MSGFORGE_GATEWAY=cobalt`) com dois números de teste, conversando
entre si. Critérios do roteiro:

| # | Critério | Resultado |
|---|---|---|
| 1 | Login por QR | ✅ (`wuid` e `profileName` no `connection.update`) |
| 2 | Envio de texto | ✅ (chega no celular; `send.message` publicado) |
| 3 | Recebimento | ✅ em tempo real, depois das correções abaixo |
| 4 | Status de entrega/leitura | ❌ os recibos chegam, mas não viram `messages.update` (LID) |
| 5 | Sessão persistida | ✅ reconecta sem QR no boot |

O teste achou quatro defeitos que os testes offline não alcançavam, todos corrigidos em
`gateway/cobalt/`: o `isConnected()` do Cobalt é true antes do scan; o `send.message` não era
publicado; o decode da sessão por `byte[]` quebra em JIDs (bug do Cobalt); e a fila offline
travava (o Cobalt não pede `offline_batch` nem manda ack do que não decodifica), o que impedia
receber. Pendente para "aceita": traduzir LID → número (remetente e status). Detalhes no
CLAUDE.md, "Problemas conhecidos".

### Segunda rodada (2026-11-12, noite)
O critério 4 passou: os `messages.update` (SERVER_ACK, DELIVERY_ACK, READ) saem a partir dos nós
do protocolo, e o `remoteJid` vem com o número (tradução de LID). A rodada achou mais três
defeitos do Cobalt, contornados em `gateway/cobalt/`: a versão do WhatsApp Web que ele busca sumiu
da página (login recusado com 405); o cache "tem WhatsApp?" descartava envios em silêncio a partir
do segundo; e envios que falham concluem com status ERROR, sem exceção. Ainda aberto, e decisivo
para "aceita": o aviso "Não foi possível confirmar seu código de segurança" no app do
destinatário (CLAUDE.md, problemas conhecidos, item 5).
