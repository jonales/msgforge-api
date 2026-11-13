# 0007: Motor do WhatsApp: Cobalt, pelo fork oficial `jonales/Cobalt`

**Status:** aceita (2026-11-13). O motor deste repositório é o Cobalt 0.1.0 do fork
[`jonales/Cobalt`](https://github.com/jonales/Cobalt), que é a versão oficial do motor para o
Msgforge. Validado ao vivo em 2026-11-12/13 (`docs/VALIDACAO-COBALT-FORK.md`). Complementada pela
[0009](0009-spike-comparativo-motores.md), que compara os motores.

## Decisão
- **Motor:** Cobalt (Java), rodando dentro da própria aplicação, sem serviço extra. Só o pacote
  `gateway/cobalt/` importa classes do Cobalt; o resto fala com o `WhatsAppGateway` (ADR 0001).
  `MSGFORGE_GATEWAY=cobalt` liga o motor; o stub continua como padrão para desenvolvimento e testes.
- **Fork oficial:** `jonales/Cobalt` é a nossa versão do motor. Defeitos de protocolo são
  corrigidos **no fork** (com teste, e validados ao vivo quando mexem no protocolo), e não com
  contornos em `gateway/cobalt/`.
- **Versão fixada:** `scripts/install-cobalt.sh` instala `com.github.auties00:cobalt-lib:0.1.0`
  a partir de um commit fixo do fork (o CI, o Dockerfile e o hook de sessão rodam o script). Uma
  correção entra aqui subindo esse commit, num PR deste repositório.
- **Manutenção do fork:**
  - branch de correção sempre a partir do `master` do fork;
  - PR no fork mergeado com merge commit, **sem squash**: o script busca o commit pelo SHA, e um
    squash deixaria o SHA fixado fora do `master` (se acontecer, re-fixe o commit);
  - o upstream do Cobalt é só leitura; o que vier de lá entra no fork por PR, como qualquer mudança.
- **Sessão:** em disco, no formato do Cobalt (`COBALT_SESSION_DIR/<instância>`, volume
  `/data/cobalt` na imagem). O `session_store` guarda só um marcador. Guardar a sessão no Postgres
  exigiria uma `LinkedWhatsAppStoreFactory` própria; fica para quando for necessário.

## Por que o Cobalt, e por que um fork
- O Cobalt roda na mesma JVM da API (um artefato só, sem um processo de outra linguagem ao lado).
  Licença MIT.
- A última versão publicada (0.0.10, jul/2025) funcionou ao vivo, mas com defeitos de protocolo
  graves (aviso de código de segurança em toda mensagem, login de ~60 s) e sem correções novas. A
  reescrita 0.1.0 do upstream não é publicada. Manter um fork nosso dá controle sobre as correções
  e sobre a versão em uso.

## Validação ao vivo
Duas instâncias locais (Docker) com dois números de teste, roteiro em
`docs/VALIDACAO-COBALT-FORK.md`. Com o fork, tudo passou:

| Item | Resultado |
|---|---|
| Login por QR, e restart sem QR | ✅ 2 a 5 s; ~2 s após restart |
| Envio e recebimento de texto | ✅ |
| Aviso de código de segurança (celular e WhatsApp Web) | ✅ não aparece |
| Mensagem de aparelho vinculado (Web) | ✅ decifrada |
| LID → número, status até "lida" | ✅ |
| Imagem, nota de voz, áudio como arquivo | ✅ |
| Fila offline (inclusive stories) | ✅ |

Correções feitas no fork durante a validação (PRs jonales/Cobalt#3 e #4): consulta de número
interativa, sessão salva reabrindo sem QR, atributo que gerava ack 479 em conta Business, sessão
Signal por LID, par LID/número aprendido do `sender_pn`, mídia (queda da JVM, imagens, nota de voz
com duração errada, áudio AAC), ack de stories na fila offline e o ICDC (`deviceListMetadata`)
igual ao do WhatsApp Web, que era a causa do aviso de código de segurança.

## Mapeamento contrato → Cobalt
| Msgforge (`WhatsAppGateway` / evento) | Cobalt |
|---|---|
| `connect` com QR / pairing code | conexão nova com o tratador de QR ou de código |
| Reconectar com sessão salva | conexão registrada a partir da pasta da sessão |
| `logout` | `logout()` e remoção do marcador e da pasta |
| `sendText`, `sendMedia`, `send(Outgoing)` | `sendMessage`, com `uploadMedia` antes para mídia |
| `qrcode.updated`, `connection.update` | tratador de QR; eventos de login e desconexão |
| `messages.upsert` | mensagens novas, convertidas por `CobaltMessageMapper` no formato do contrato |
| `messages.update` | `ack`/`receipt` do protocolo (`SentStatusTracker`) |

Detalhes de comportamento (lock por instância, `resolve`, `LidResolver`, mídia) estão no
`CLAUDE.md`, seção "Arquitetura".

## Consequências
- Somos donos do motor: mudanças de protocolo do WhatsApp podem exigir correção no fork, e cada
  correção precisa de teste e, quando mexe no protocolo, de uma rodada ao vivo.
- O isolamento atrás de `WhatsAppGateway` mantém a API independente do motor; a variante em Go
  (whatsmeow) vive em outro repositório (ADR 0009).
- O CI não alcança o WhatsApp, e não deve: os testes do gateway usam um proxy numa porta fechada
  (`COBALT_PROXY`). O fluxo real só se valida ao vivo.
- Pendências conhecidas do motor ficam no `CLAUDE.md` ("Problemas conhecidos", item 5).

## Histórico
- **2026-11, spike offline (0.0.10):** disponibilidade no Maven Central, Java 25, chaves e sessão
  ok; dependência comercial (`aspose-words`) contornada com um shim; protocolo não verificado.
- **2026-11-12, ao vivo com a 0.0.10:** login, envio, recebimento, status e sessão passaram após
  sete correções em `gateway/cobalt/`; ficaram o aviso de código de segurança e o login de ~60 s.
- **2026-11-12, migração para o fork 0.1.0:** os contornos da 0.0.10 foram removidos (`WebVersion`,
  `OfflineQueueGuard`, `RawNodes`, `UsersCache`, `DatabaseSessionSerializer`, `CobaltSessionCodec`
  e o shim do `aspose-words`); a sessão foi para o disco.
- **2026-11-12/13, validação com o fork:** todos os itens passaram (tabela acima). ADR aceita, com
  o fork como versão oficial do motor.
