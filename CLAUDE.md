# Msgforge API: memória do projeto

API REST de WhatsApp em Java 25 + Spring Boot 4.1. O **contrato público** (rotas, header `apikey`,
formato de erro e envelope de webhook) é estável e está fixado em `docs/COMPATIBILIDADE.md`:
clientes existentes dependem dele. Antes de mudar qualquer rota, campo ou evento, confira esse
documento.

## Comandos

```bash
mvn test                                     # unitários (*Test), rápido, sem infraestrutura
mvn verify                                   # + integração (*IT): Testcontainers, ou serviços locais (abaixo)
mvn spring-boot:run                          # sobe local (Postgres + Redis em localhost)
docker compose up -d --build                 # stack completa
scripts/new-migration.sh "descricao"         # nova migration Flyway no padrão
graphify update .                            # atualiza o grafo de código (sem LLM)
```

Nas sessões em nuvem, `.claude/hooks/session-start.sh` instala o JDK 25, baixa as dependências
do Maven e o Graphify, e gera o grafo.

**Testes de integração sem Docker** (caso das sessões em nuvem): suba Postgres 16 e Redis locais
(`initdb`/`pg_ctl` + `redis-server --daemonize yes`), crie o banco `msgforge_it` (dono `msgforge`)
e rode `IT_DATABASE_URL=jdbc:postgresql://localhost:5432/msgforge_it mvn verify`. Variáveis em
`src/test/java/dev/msgforge/support/TestInfrastructure.java`. Sem Docker e sem essa variável,
os `*IT` são pulados, não falham. No CI (`.github/workflows/ci.yml`) eles usam Testcontainers.

## Arquitetura (pacote `dev.msgforge`)

- `instance/`, `message/`, `chat/`, `group/`, `webhook/`: controllers e DTOs (records) no formato JSON do contrato.
- `gateway/`: `WhatsAppGateway` é a **única** fronteira com o protocolo. Dois motores:
  `StubWhatsAppGateway` (`MSGFORGE_GATEWAY=stub`, padrão) e `gateway/cobalt/CobaltWhatsAppGateway`
  (`MSGFORGE_GATEWAY=cobalt`, ADR 0007). Nada fora de `gateway/cobalt/` pode importar classes do
  Cobalt (`com.github.auties00.cobalt.*`).
  - **Motor: Cobalt 0.1.0 do fork `jonales/Cobalt`** (`cobalt-lib`), fora do Maven Central:
    `scripts/install-cobalt.sh` instala o commit fixado no script (o CI, o Dockerfile e o hook de
    sessão rodam o script antes da build). Para testar outra versão do motor, troque o commit no
    script. API bloqueante (sem `CompletableFuture`): o gateway chama o Cobalt por `call(...)`, com
    o teto `COBALT_SEND_TIMEOUT_MS`.
  - `CobaltMessageMapper`: mensagens do Cobalt → JSON Baileys (`messages.upsert`/`update`).
  - **Sessão em disco**, no formato do Cobalt: uma pasta por instância em `COBALT_SESSION_DIR`
    (padrão `~/.msgforge/cobalt`; na imagem Docker, o volume `/data/cobalt`). O `session_store`
    guarda só um marcador (`SESSION_MARKER`), que o `SessionRestorer` e o `InstanceService`
    consultam. Marcador sem pasta (volume perdido, ou sessão gravada pela 0.0.10) é apagado e a
    instância pede QR de novo. Logout apaga o marcador e a pasta.
  - Testes do Cobalt nunca falam com o WhatsApp: `COBALT_PROXY` aponta para uma porta fechada
    (`CobaltWhatsAppGatewayTest`, `CobaltGatewayIT`). Para manter um connect **pendente**, use
    um proxy "mudo" (aceita e não responde), como em `concurrentConnectsShareOnePendingConnection`.
  - `connect`/`logout` são serializados por instância (lock): nunca dois clientes do WhatsApp para
    a mesma sessão. Um `connect` com QR já pendente **reaproveita** a conexão e devolve o QR atual;
    pedir pairing code recomeça. O `connect()` do Cobalt bloqueia no handshake e roda numa thread
    virtual própria; antes do login, um erro de conexão vira `DISCONNECT` no tratador de erros
    (fecha a instância e libera quem espera o QR).
  - Regras herdadas do teste ao vivo (valem também para o fork):
    - "Logada" é o `Connection.loggedIn` (evento de login), nunca o socket aberto.
    - Todo envio passa por `resolve` (consulta o número e usa o JID devolvido: no Brasil, o número
      com o 9 resolve sem ele; sem JID, 400 `{exists: false}`).
    - LID → número: `LidResolver` consulta o Store do Cobalt (`findPhoneByLid`) e aprende com o
      `sender_pn`/`participant_pn` dos nós; sem tradução, sai `<lid>@lid`, nunca um telefone falso.
      `SentStatusTracker` gera os `messages.update` a partir dos `ack`/`receipt`.
    - Mídia: monta a mensagem, envia ao CDN com `uploadMedia` (o Cobalt preenche chaves e URL) e só
      então `sendMessage`.
    - O WhatsApp pode pedir a passkey do aparelho numa sessão conectada ("checkpoint"); o link vai
      para o log (`passkeyCheckpoint`).
  - Os contornos da 0.0.10 (`WebVersion`, `OfflineQueueGuard`, `RawNodes`, `UsersCache`,
    `DatabaseSessionSerializer` e o shim do aspose) foram removidos: o fork resolve versão do
    WhatsApp Web, fila offline e cache de números. Se um desses sintomas voltar, confira o motor
    antes de recriar o contorno.
  - Diagnóstico do protocolo: `LOGGING_LEVEL_DEV_MSGFORGE_GATEWAY_COBALT_WIRE=DEBUG` registra os
    nós enviados e recebidos (só nomes e atributos, nunca o conteúdo).
- Envios além de texto/mídia passam por `WhatsAppGateway.send(Instance, Outgoing)`, com
  `Outgoing` **selado** (VoiceNote, Location, Contacts, Reaction, Poll): um tipo novo obriga stub e
  Cobalt a tratá-lo. vCard é montado em `message/VCards` (com escape, contra injeção de campos).
  Enquete no Cobalt: `PollCreationMessage` com `selectableOptionsCount` e chave aleatória.
- `/chat/*` (`ChatService`) usa `checkNumbers`, `markRead`, `sendPresence`, `fetchProfile`,
  `profilePictureUrl` e `updateProfile` (`ProfileUpdate` selado). Foto e recado escondidos pela
  privacidade viram null, não erro. `markRead` de mensagem fora do Store do Cobalt marca a conversa
  inteira. No Cobalt, números passam por `resolve`/`resolveMany` (usam o JID devolvido); grupos
  traduzem LID pelo `LidResolver` da conexão, e `updateParticipants` devolve os JIDs **pedidos** (o WhatsApp responde pelo resolvido). Ainda não testados ao vivo: `markRead` em
  conversa endereçada por LID e o mapeamento de grupos com LID.
- `/group/*` (`GroupService` valida `groupJid` e participantes; o gateway recebe JIDs prontos).
  O stub guarda os grupos **em memória** por instância, para o `GroupIT` exercitar o fluxo
  inteiro. No Cobalt, `fetchAllGroups` usa `queryParticipatingGroups` (uma consulta só).
- `history/` (ADR 0008): tabela `message` por **SQL direto** (`MessageHistory`, sem entidade JPA).
  O `HistoryRecorder` grava as recebidas (`MessageReceived`), as enviadas (no `created()` do
  `MessageController`) e os status (`MessageStatusUpdated`, que **só avançam**). Repetidas são
  ignoradas (`ON CONFLICT`). Falha ao gravar só vai para o log. `HistoryRetention` (`@Scheduled`, de
  hora em hora) apaga em lotes o que passou de `HISTORY_RETENTION_DAYS`. Mensagem sem `status`
  (ou status desconhecido) entra como `PENDING`: `List.of(...).contains(null)` lança NPE.
- `gateway/media/`: a imagem Docker traz o ffmpeg (o Cobalt usa o `ffprobe` para a duração).
  `AudioTranscoder` converte a voz para OGG/Opus por stdin/stdout (sem disco), quando `encoding`
  não é false e o áudio ainda não é Opus; sem ffmpeg, envia como veio (aviso no log).
  `MediaSignatures` recusa um "vídeo" sem assinatura MP4/WebM antes do ffprobe (que travaria ~10 s).
- `instance/InstanceService`: `create`/`connect`/`logout`/`delete` **não seguram transação**
  enquanto o gateway trabalha (os eventos do motor chegam por outras threads e precisam ver a
  instância commitada).
- `event/`: o gateway publica `GatewayEvent` (interface selada) e o `EventDispatcher` converte
  no envelope do contrato e entrega no webhook da instância, no webhook global e no RabbitMQ.
  Um evento novo exige um `case` novo nos `switch` exaustivos; isso é proposital.
  **A entrega roda depois do commit e usa só um snapshot (`DeliveryTarget`)**; a thread de
  entrega nunca lê o banco (ADR 0004). As entregas de uma instância saem **em ordem**, numa fila
  por instância com teto de pendências (`OrderedExecutor`, ADR 0006). `WebhookSender` cuida de
  timeout, retry e `Content-Length`; `sendUntrusted` (webhook de instância) passa pelo
  `security/PrivateNetworkGuard` quando `WEBHOOK_BLOCK_PRIVATE_NETWORKS=true`.
- `gateway/session/SessionStore`: blob da sessão do WhatsApp por instância (`session_store`).
  `SessionRestorer` reconecta no boot quem tem sessão; logout apaga a sessão.
- `security/ApiKeyFilter`: a chave global libera tudo; o token da instância libera só as rotas
  `/{grupo}/{acao}/{instancia}`. Erros de chave contam por IP (`ApiKeyAttemptLimiter`, sobre a
  mesma `FailureWindow` do login do Manager): passou de `AUTHENTICATION_MAX_FAILURES` em 15 min,
  429 em tudo, **inclusive com a chave certa** (senão o 200 entregaria a chave). Num IT que esgota
  o limite, use contexto próprio (`@TestPropertySource`), como o `ApiKeyLimitIT`: todos os ITs
  chamam de 127.0.0.1.
- `common/ApiExceptionHandler`: erros no formato `{status, error, response: {message: [...]}}`.
  Violação de constraint vira 400 (pelo nome da constraint); exceções do Spring com status
  (`ErrorResponse`) mantêm o status; o resto vira 500 sem detalhes.
- `web/`: o **Manager**, front-end em Thymeleaf em `/manager` (ADR 0005). Veja a seção
  "Front-end" abaixo.

## Convenções

- **Banco:** só via Flyway. Siga `docs/MIGRATIONS.md` (nome `V{yyyyMMddHHmm}__desc.sql`, nunca
  editar uma migration que já foi para `main`, entidade JPA no mesmo commit). `ddl-auto: validate`.
- **Nomes de rotas, campos e eventos:** os do contrato (`docs/COMPATIBILIDADE.md`), mesmo quando
  parecerem estranhos. `ContractNamingTest` protege os nomes de eventos e JIDs.
- **Java 25:** records para DTOs, `sealed` + pattern matching, virtual threads
  (`spring.threads.virtual.enabled`). Injeção por construtor, sem Lombok.
- **Spring Boot 4:** Jackson 3 (`tools.jackson.*`; as anotações continuam em
  `com.fasterxml.jackson.annotation`). Os starters são modulares: um auto-config que "sumiu"
  costuma ser um starter faltando (o `spring-boot-starter-restclient`, por exemplo).
- **Identidade:** o projeto se apresenta só como Msgforge. Não cite outros produtos como origem ou
  referência em README, código, comentários, configuração, ADRs ou neste arquivo (decisão do
  usuário, 2026-11). A única exceção é `docs/COMPATIBILIDADE.md`, que documenta de onde vem o
  contrato. Ao mudar rotas, eventos ou variáveis, atualize também as tabelas do README.
- **Commits:** em português, no estilo Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`).
- **Decisões de arquitetura:** registre em `docs/decisoes/` (ADR curto, numerado).

## Front-end (Manager com Thymeleaf)

ADR 0005 (seção "Implementação"). Para criar ou alterar uma tela, use a skill `nova-tela`.
- Thymeleaf renderizado no servidor, rota base `/manager`, sem
  SPA e sem Node/npm. Interatividade com htmx vendorizado (sem CDN).
- Controllers MVC em `dev.msgforge.web` chamam os **serviços**, nunca repositórios nem a
  API por HTTP. Os modelos de tela são records próprios; os DTOs da API são o contrato público e
  não são reaproveitados.
- Segurança: `ManagerSecurityConfig` aplica o Spring Security **só** em `/manager/**` (login com a
  chave global, sessão, CSRF, CSP só `'self'`, cookie `SameSite=Strict`). A API tem uma cadeia
  aberta, sem sessão e sem headers; quem a protege é o `ApiKeyFilter`. Não use estilo ou script
  inline: o CSP bloqueia.
  - Login limitado a 10 falhas por IP em 15 min (`LoginAttemptLimiter`). **Conte falhas no
    failure handler, nunca no `AuthenticationProvider`**: o Boot registra o provider também no
    `AuthenticationManager` pai, e numa chave errada ele roda duas vezes.
  - Sem sessão, o entry point manda para o login; no polling do htmx (`HX-Request`) responde
    401 + `HX-Redirect`. Não use `defaultAuthenticationEntryPointFor` sozinho: ele substitui o
    entry point do form login para todas as requisições.
- Modelos de tela (`ManagerViews`) precisam ser **públicos**: o SpEL do Thymeleaf não chama
  métodos de records não públicos. Erro de renderização não passa pelo
  `ManagerExceptionHandler` (vira página 500); teste cada tela no `ManagerIT`.
- E2E: suba a aplicação (stub) e rode `node scripts/e2e/manager.js [pasta-screenshots]`
  (login → criar → conectar → webhook → erro → excluir → sair; falha se o console tiver erro,
  inclusive de CSP).
- Templates em `templates/manager/` (fragmentos em `fragments/`), textos em
  `messages.properties` (pt-BR).
- Telas da v1 (todas entregues):
  - [x] login
  - [x] lista de instâncias com status
  - [x] criar instância
  - [x] conectar (QR/pairing ao vivo)
  - [x] webhook
  - [x] mensagem de teste
  - [x] logout e exclusão
  - [x] mensagens: conversas e conversa aberta, lidas do histórico (`/manager/instances/{n}/messages`,
    atualiza por htmx; o envio de teste do Manager também vai para o histórico)
- Próximas ideias (não planejadas): webhook global, envio de mídia.

## Estado atual e backlog

O roadmap está no `README.md`. As Fases 1.5 e 1.6 terminaram. **Fase 2:** o
`CobaltWhatsAppGateway` foi **validado ao vivo** em 2026-11-12, com duas instâncias locais
(Docker) conversando entre si: login por QR, envio, recebimento em tempo real e sessão restaurada
no boot funcionam. A ADR 0007 continua *proposta* até os status (`messages.update`) funcionarem
(item 5). O roteiro está em `docs/spikes/cobalt/README.md`. Na nuvem não dá para testar ao vivo
(o proxy bloqueia `web.whatsapp.com`). Se o Cobalt travar de vez, a alternativa preferida é um
sidecar com Baileys (só troca `gateway/`).

Problemas conhecidos (mantenha esta lista atual; ao corrigir um item, tire-o daqui no mesmo PR):
1. `mentioned`, `quoted`, `linkPreview` e `webhookBase64` são aceitos e ignorados; `delay` só
   funciona no Cobalt ("digitando..."). Mídia recebida vai no `messages.upsert` sem download/base64.
   Figurinha, botões e listas ainda não existem.
2. O RabbitMQ usa um exchange tópico, e não filas por evento (`docs/COMPATIBILIDADE.md`).
3. As entregas de webhook ficam só em memória (com teto por instância, ADR 0006): se o processo
   cair durante os retries, o evento se perde. Uma fila persistente (outbox) resolveria; ainda
   não é necessário.
4. `PrivateNetworkGuard` não cobre DNS rebinding (resolve antes do connect). Vem desligado por
   padrão.
5. **Cobalt ao vivo, pendências** (observadas na 0.0.10). **Próximo passo: rodar
   `docs/VALIDACAO-COBALT-FORK.md`** (roteiro com o fork 0.1.0, PR #12) e tirar daqui o que ele
   resolver. (a) O app oficial do destinatário mostra "Não foi possível
   confirmar seu código de segurança" em mensagens vindas do Cobalt. Evidências: a chave de
   identidade é estável entre restarts; **toda** mensagem do Cobalt sai como `pkmsg` (a sessão
   Signal nunca se estabiliza); a cada login o Cobalt reenvia as chaves (`iq encrypt`). Suspeitas:
   sessões que não se fixam, ou a verificação automática de chaves do WhatsApp não aceita o
   aparelho. (b) O `pushName` enviado é o nome do aparelho, e após restaurar a sessão o
   `profileName` vem nulo. (c) O login (novo ou restaurado) só conclui ~60 s depois, após um iq
   `w`/`props` sem resposta. (d) O Cobalt não atende `receipt type=retry` (aparelho que não
   decifrou pede reenvio). Ainda não observados: texto recebido como `extendedTextMessage` e o
   timestamp da reação.
6. Histórico (ADR 0008): o que chegou antes de a instância receber eventos não aparece. A
   retenção (`HISTORY_RETENTION_DAYS`) vem desligada por padrão.

## Memória entre sessões

- **Este arquivo:** fatos estáveis (arquitetura, convenções, backlog). Mantenha curto e atual.
- **`docs/decisoes/`:** o *porquê* das escolhas grandes.
- **`graphify-out/memory/`:** perguntas e respostas sobre o código, salvas com
  `graphify save-result`. Use quando descobrir algo que não é óbvio no código ou quando um
  caminho se mostrar um beco sem saída:
  ```bash
  graphify save-result --question "..." --answer "..." --nodes ClasseA ClasseB \
    --outcome useful        # ou dead_end, ou corrected --correction "..."
  graphify reflect --graph graphify-out/graph.json   # consolida em graphify-out/reflections/LESSONS.md
  ```
  Leia `graphify-out/reflections/LESSONS.md` antes de mexer numa área que você não conhece.
  Escreva o `--question` **sem acentos**: ele vira o nome do arquivo, e nomes ASCII evitam
  problemas no macOS e no Windows.
- **Testes de concorrência:** nunca presuma que uma thread "já começou". Sincronize com latch
  (veja `OrderedExecutorTest`) e rode o teste centenas de vezes (`@RepeatedTest` temporário)
  antes de confiar nele.
- O grafo (`graph.json`, `GRAPH_REPORT.md`, `graph.html`) é **gerado** e não é versionado.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- When graphify-out/graph.json exists, run `graphify query "<question>"` before your first grep, search or source-file read for a codebase question, and put this rule in every subagent prompt that explores code. Read raw files only for lines the graph or an earlier read already located. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- If graphify-out/graph.json does not exist (graphify not installed), fall back to normal search.
