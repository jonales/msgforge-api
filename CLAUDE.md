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
  - **Motor: Cobalt 0.1.0 de `jonales/cobalt-2`** (`cobalt-lib`), fora do Maven Central:
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
  - Regras herdadas do teste ao vivo (valem também para o `cobalt-2`):
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
    - Mídia passa pelo transcodificador do motor (ffmpeg nativo), que lê MP3, OGG e WAV. Nota de
      voz (`ptt`) sai em OGG/Opus a 16 kHz; o granule precisa estar em 48 kHz, ou o WhatsApp não
      baixa o áudio. Áudio comum vira AAC em quadros de `frame_size` fixo.
    - Recusa de chamada: o `IncomingCall` traz o número de quem liga, mas a recusa leva o criador
      da oferta (LID) e sai como a do celular (`count="0"`, sem `reason`); senão o servidor aceita e
      ignora, e a chamada continua tocando (cobalt-2#2).
    - Toda mensagem leva o `deviceListMetadata` (ICDC) com o `ts` da lista de aparelhos do
      remetente. Ele precisa ser o `ts` **assinado** da lista de índices de chave, em segundos: com
      o horário da consulta, o WhatsApp Web de quem recebe mostrava "Não foi possível confirmar
      seu código de segurança" a cada mensagem (o whatsmeow nem envia o campo).
  - Os contornos da 0.0.10 (`WebVersion`, `OfflineQueueGuard`, `RawNodes`, `UsersCache`,
    `DatabaseSessionSerializer` e o shim do aspose) foram removidos: o motor resolve versão do
    WhatsApp Web, fila offline e cache de números. Se um desses sintomas voltar, confira o motor
    antes de recriar o contorno.
  - Nome: no Cobalt, o nome do aparelho (`CONFIG_SESSION_PHONE_CLIENT`) e o nome do perfil ocupam
    o mesmo campo, que vai como `pushName` no login. Só a conexão nova recebe o nome do aparelho;
    a sessão restaurada mantém o nome do perfil sincronizado (`setting_pushName`).
  - Histórico no pareamento: `Instance.historySync` (`NONE` padrão das novas, `RECENT`, `FULL`),
    escolhido no Manager (card "Conectar" e criar) e aplicado só ao criar a conexão nova
    (`discardHistory`/`defaultHistory`/`fullHistory` do Cobalt). Não é rota da API (settings
    estão fora do contrato).
  - Sessão salva que o motor não consegue abrir **não** é apagada: o connect falha com
    `GatewayException` e o marcador e a pasta ficam (antes, virava QR novo por cima da sessão).
  - Diagnóstico do protocolo: `LOGGING_LEVEL_DEV_MSGFORGE_GATEWAY_COBALT_WIRE=DEBUG` registra os
    nós enviados e recebidos (só nomes e atributos, nunca o conteúdo).
- Configurações do `/instance/create` (`rejectCall`, `msgCall`, `groupsIgnore`, `alwaysOnline`,
  `readMessages`, `readStatus`) ficam em `InstanceSettings` (embeddable, com as regras puras
  `delivers`/`marksAsRead`); `syncFullHistory: true` vira `historySync = FULL`. O proxy da instância
  (`InstanceProxy`) vence o `COBALT_PROXY`; a senha vai cifrada por `security/SecretCipher`
  (AES-GCM, `SECRETS_ENCRYPTION_KEY`, ADR 0014) e só o Cobalt a decifra, ao conectar. Sem a chave,
  create com `proxyPassword` dá 400. Depois do create: `/settings/set|find` e `/proxy/set|find`
  (`instance/SettingsController`). As configurações valem na hora (`WhatsAppGateway.settingsChanged`
  troca o `Connection.settings`, volátil, que cada evento lê); o proxy só numa conexão nova. O `find`
  do proxy nunca devolve a senha. Erros de validação ligados a um campo usam `InvalidFieldException`
  (`field` + `reason`), que o Manager traduz para `manager.<form>.error.<field>.<reason>`.
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
  Um evento novo exige um `case` novo nos `switch` exaustivos; isso é proposital. Os eventos
  trazem dados neutros e o `data` do contrato é montado em `EventPayloads` (formatos em
  `docs/eventos.md`). Evento que nenhum destino assina (`webhook.events`, `WEBHOOK_GLOBAL_EVENTS`,
  `RABBITMQ_EVENTS`) nem é montado nem vai para a outbox. `APPLICATION_STARTUP` (`StartupAnnouncer`)
  não tem instância: só global e RabbitMQ, chave de ordem `""`. `GROUP_UPDATE` sai como
  `groups.update` e `GROUP_PARTICIPANTS_UPDATE` como `group-participants.update` (`EventType`).
  No Cobalt, `CobaltEvents` converte contatos, conversas, presença, app state, histórico, chamadas,
  REVOKE e as notificações `w:gp2` (o motor aplica no Store, mas não avisa listener de grupo).
  **A entrega roda depois do commit e usa só um snapshot (`DeliveryTarget`)**; a thread de
  entrega nunca lê o banco (ADR 0004). As entregas de uma instância saem **em ordem** (ADR 0006).
  **Outbox** (ADR 0012, `event/outbox/`): o dispatcher grava cada entrega na tabela `outbox`
  (payload `json`, texto exato) e o `OutboxWorker` entrega só a cabeça de cada chave (tipo +
  instância) com `FOR UPDATE SKIP LOCKED`; retry agendado na linha, `MORTO` após N tentativas;
  `LISTEN/NOTIFY` entre nós. `OUTBOX_ENABLED=false` volta ao `OrderedExecutor`. `WebhookSender` cuida de
  timeout, retry e `Content-Length`; `sendUntrusted` (webhook de instância) passa pelo
  `security/PrivateNetworkGuard` quando `WEBHOOK_BLOCK_PRIVATE_NETWORKS=true`.
- `notification/` (ADR 0011): `NotificationService.send(Notification)` entrega por todos os
  `NotificationChannel` ligados; o primeiro é o `EmailChannel`. Dentro de transação, enfileira só
  no afterCommit. SMTP por `SMTP_*` (`SmtpProperties`); o `EmailSender` monta o `JavaMailSender`
  ele mesmo (não o auto-config `spring.mail.*`), então sem `SMTP_HOST` não há bean pela metade e
  `isEmailEnabled()` é false. O e-mail é montado na hora (`EmailRenderer`, motor Thymeleaf próprio
  com HTML + TEXT em `templates/email/`, assunto em `email.<modelo>.subject`) e vai para a outbox
  como `EMAIL` (chave = destinatário, payload = `EmailMessage`, nunca a credencial). O
  `OutboxWorker` entrega com o retry dos webhooks; um nó sem SMTP não pega linhas `EMAIL`
  (`claim` por tipos), o que também isola os ITs (só o `EmailIT` tem GreenMail). Com
  `OUTBOX_ENABLED=false`, fila em memória por destinatário (`OrderedExecutor`). Endereço passa por
  `EmailAddresses.normalize` (um endereço só, sem nome nem quebra de linha). Tipo novo de
  notificação: valor em `Notification.Kind` + os dois modelos + o assunto. Tela `/manager/server`
  (`ServerController`): estado do SMTP e e-mail de teste.
- `gateway/session/SessionStore`: blob da sessão do WhatsApp por instância (`session_store`).
  `SessionRestorer` reconecta no boot quem tem sessão; logout apaga a sessão.
- `security/ApiKeyFilter`: a chave global libera tudo; o token da instância libera só as rotas
  `/{grupo}/{acao}/{instancia}`. Erros de chave contam por IP (`ApiKeyAttemptLimiter`, sobre a
  mesma `FailureWindow` do login do Manager): passou de `AUTHENTICATION_MAX_FAILURES` em 15 min,
  429 em tudo, **inclusive com a chave certa** (senão o 200 entregaria a chave). Num IT que esgota
  o limite, use contexto próprio (`@TestPropertySource`), como o `ApiKeyLimitIT`: todos os ITs
  chamam de 127.0.0.1 (e dois ITs com as mesmas propriedades dividem o contexto e o bloqueio).
  - `ApiKeyStartupCheck`: fora do perfil `dev` a aplicação **não sobe** com a chave padrão, vazia
    ou com menos de 32 caracteres (o `mvn spring-boot:run` já usa `dev`; os ITs usam chave longa).
    Tokens de instância novos também exigem 32+ caracteres (os antigos continuam valendo).
  - IP real atrás de proxy: `RemoteIpValve` do Tomcat (`forward-headers-strategy: native`), que só
    aceita o `X-Forwarded-For` de `TRUSTED_PROXIES` (CIDRs; vazio = ignora). Os limitadores
    continuam lendo `getRemoteAddr()`.
- `common/ApiExceptionHandler`: erros no formato `{status, error, response: {message: [...]}}`.
  Violação de constraint vira 400 (pelo nome da constraint); exceções do Spring com status
  (`ErrorResponse`) mantêm o status; o resto vira 500 sem detalhes.
- `web/`: o **Manager**, front-end em Thymeleaf em `/manager` (ADR 0005). Veja a seção
  "Front-end" abaixo.
- `access/` (ADR 0010, seção "Fase 2"): organizações, usuários (`app_user`), papéis e vínculos.
  Catálogo **fixo** de permissões (`Permission`); papéis são **dados** de cada organização
  (`role` + `role_permission`), com os modelos de `RoleTemplate`. Toda instância tem
  `organization_id` (padrão: a organização `...0001`, onde a API cria). A pergunta única é
  `AccessService.can(quem, permissão, instância)`; as regras puras ficam em `AccessRules`. A sessão
  guarda só o `ManagerPrincipal` (id e e-mail; chave global = `GLOBAL_KEY`); as permissões são
  lidas do banco a cada pergunta. As travas (sem escalada, último gestor de papéis, papel em uso)
  ficam no `AccessAdminService`. Permissão nova: migration no `CHECK` de `role_permission` e textos
  `manager.permission.*` (o `PermissionCatalogTest` cobra os dois). Nos ITs, limpe com
  `AccessFixtures.reset` (o `TRUNCATE instance` não alcança usuários).

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
  usuário, 2026-11). Exceções: `docs/COMPATIBILIDADE.md`, que documenta de onde vem o contrato,
  e `THIRD-PARTY-NOTICES.md`, com os avisos que as licenças das bibliotecas distribuídas exigem
  (2026-11-12). Citar a biblioteca do motor (Cobalt) na documentação técnica e nas ADRs é
  permitido. Ao mudar rotas, eventos ou variáveis, atualize também `docs/api.md`, `docs/eventos.md`
  ou `docs/configuracao.md`. O README é só apresentação e aponta para `docs/`.
- **Commits:** em português, no estilo Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`).
- **Decisões de arquitetura:** registre em `docs/decisoes/` (ADR curto, numerado).

## Front-end (Manager com Thymeleaf)

ADR 0005 (seção "Implementação"). Para criar ou alterar uma tela, use a skill `nova-tela` (estrutura)
e a `ui-ux-pro-max` (design e UX), respeitando o CSP e os textos em `messages.properties`.
- Thymeleaf renderizado no servidor, rota base `/manager`, sem
  SPA e sem Node/npm. Interatividade com htmx vendorizado (sem CDN).
- Controllers MVC em `dev.msgforge.web` chamam os **serviços**, nunca repositórios nem a
  API por HTTP. Os modelos de tela são records próprios; os DTOs da API são o contrato público e
  não são reaproveitados.
- Segurança: `ManagerSecurityConfig` aplica o Spring Security **só** em `/manager/**` (login por
  e-mail e senha ou pela chave global, sessão, CSRF, CSP só `'self'`, cookie `SameSite=Strict`). A API tem uma cadeia
  aberta, sem sessão e sem headers; quem a protege é o `ApiKeyFilter`. Não use estilo ou script
  inline: o CSP bloqueia.
  - Login limitado a 10 falhas por IP em 15 min (`LoginAttemptLimiter`). **Conte falhas no
    failure handler, nunca no `AuthenticationProvider`**: o Boot registra o provider também no
    `AuthenticationManager` pai, e numa chave errada ele roda duas vezes.
  - Um POST de login só: com o campo `apikey` é a chave (entra como admin da plataforma; sem
    usuários sempre, depois só com `MANAGER_KEY_LOGIN=true`, o padrão), senão `username` (e-mail) e
    `password` (BCrypt). Bloqueado com a senha certa vai para `?disabled`, sem contar falha. O
    `ActiveSessionFilter` derruba a sessão de quem foi bloqueado (não é `@Component`: viraria
    filtro da API).
  - Sem sessão, o entry point manda para o login; no polling do htmx (`HX-Request`) responde
    401 + `HX-Redirect`. Não use `defaultAuthenticationEntryPointFor` sozinho: ele substitui o
    entry point do form login para todas as requisições.
- Modelos de tela (`ManagerViews`) precisam ser **públicos**: o SpEL do Thymeleaf não chama
  métodos de records não públicos. Erro de renderização não passa pelo
  `ManagerExceptionHandler` (vira página 500); teste cada tela no `ManagerIT`.
- Tema (Sistema, Claro, Escuro): cookie `msgforge_theme` gravado pelo `ThemeController`
  (POST `/manager/theme`, menu da conta). O `<html>` de cada tela leva o `data-theme` pelo
  `ManagerTheme` (`th:with="pageTheme=${@managerTheme.attribute()}" th:data-theme="${pageTheme}"`;
  o bean direto no `th:data-theme`/`th:attr` cai na restrição do Thymeleaf). Sem cookie, segue o
  `prefers-color-scheme`. Tokens pastel no `:root` do `manager.css`, contraste AA medido; o bloco
  escuro existe duas vezes (pelo sistema e escolhido) e os dois precisam ficar iguais.
- Tempo real (ADR 0013): SSE em `/manager/stream/...` (`ManagerStreamController`/`ManagerStreams`)
  envia os **mesmos fragmentos** do polling (`sse-swap`, extensão `sse` vendorizada). Origem só pelo
  `ManagerBus` (hoje local; Redis pub/sub para multi-nó). O polling fica como reserva
  (`data-poll` + `static/manager/manager.js`); `ManagerStreamIT` cobre o stream.
- E2E: suba a aplicação (stub) e rode `node scripts/e2e/manager.js [pasta-screenshots]`
  (login → criar → conectar → webhook → erro → excluir → sair; falha se o console tiver erro,
  inclusive de CSP).
- Templates em `templates/manager/` (fragmentos em `fragments/`), textos em
  `messages.properties` (pt-BR).
- Telas da v1 (todas entregues):
  - [x] login
  - [x] lista de instâncias com status
  - [x] página inicial (`/manager`): uma ação principal por estado (`InstanceRow.action()`: abrir,
    continuar a conexão, ver conexão, reconectar), número e perfil, aviso de webhook em nova tentativa
    (`DeliveryHealth`, uma consulta à fila viva da outbox), estado vazio que leva à criação guiada,
    busca e filtro no navegador só acima de 8 instâncias (`SEARCH_THRESHOLD`), e canais e integrações
    "Em breve" sem link. Escala de títulos em `--fs-*` no `manager.css`
  - [x] criar instância
  - [x] conectar (QR/pairing ao vivo)
  - [x] webhook
  - [x] mensagem de teste
  - [x] logout e exclusão
  - [x] histórico no pareamento por instância (rádios no card "Conectar", select no criar)
  - [x] configurações da instância (interruptores, histórico, proxy recolhido em `<details>`; a senha
    nunca volta para a tela e, em branco, a guardada fica)
  - [x] mensagens: conversas e conversa aberta, lidas do histórico (`/manager/instances/{n}/messages`,
    atualiza por htmx; o envio de teste do Manager também vai para o histórico)
  - [x] nova instância guiada (`NewInstanceController`): `/manager/instances/new` → `/new/connection`
    (QR ou código, com número) → `/new/history` (cria e conecta) → `/{n}/connect` (QR ao vivo, o
    mesmo trecho `instance :: live`). Uma página por passo, rascunho na sessão: voltar e recarregar
    funcionam sem JS. Nunca conectada (`ownerJid` nulo) aparece como "Aguardando conexão" e o painel
    retoma. Os nomes `new` e `table` ficam reservados no Manager (rotas fixas em `/manager/instances/`).
  - [x] usuários e papéis (`AccessController`, `/manager/users` com `USERS_MANAGE` e
    `/manager/roles` com `ROLES_MANAGE`; link "Usuários" no topo, ao lado de "Sair"). Sem a
    permissão, a tela "Sem acesso" com 403.
  - [x] filtro por papel (fatia 5 da ADR 0010): toda rota com `{nome}` passa por
    `ManagerAccess.require(nome, permissão)`. Instância que a pessoa não vê (outra organização ou
    fora do escopo) dá 404 igual à que não existe; vê, mas sem a permissão, 403 "Sem acesso". A
    lista mostra só o que tem "ver status"; o painel esconde as seções sem permissão (`PanelAccess`)
    e o QR só vai para quem pode conectar (`Live.codeHidden`). No SSE, cada assinante leva o
    `ManagerPrincipal` e cada envio relê as permissões (`AccessService.check`, uma leitura por
    pessoa); a lista é renderizada uma vez **por conteúdo distinto**, nunca uma vez para todos.
    Nos ITs, entre no Manager com o `ManagerPrincipal` (login de verdade ou `authentication(...)`):
    um `user("manager")` qualquer não vê nada (falha fechada).
- Planejado (fase 0 desenhada em 2026-11-13): redesenho do Manager, organizações, usuários e papéis,
  link de conexão para o cliente, e-mail (SMTP) e alertas, outbox e telas ao vivo por SSE. Produto
  em `PRODUCT.md` (raiz); decisões nas ADRs 0010 a 0013; fases em `docs/roadmap.md`. Integrações e
  canais sem backend aparecem como "Em breve", nunca como botão que falha.

## Estado atual e backlog

O roadmap está em `docs/roadmap.md`. As Fases 1.5 e 1.6 terminaram. **Fase 2:** o
`CobaltWhatsAppGateway` foi validado ao vivo com a 0.0.10 em 2026-11-12 (duas instâncias locais
conversando entre si), migrou para o fork 0.1.0 (PR #12) e foi validado ao vivo com o fork em
2026-11-13 (ADR 0007 aceita). **O `jonales/cobalt-2` é a versão oficial do motor** (repositório
independente, derivado do Cobalt; o antigo fork `jonales/Cobalt` foi arquivado em 2026-11-13): os
defeitos são corrigidos no `jonales/cobalt-2` (branch a partir do `master`, PR com merge commit,
nunca squash), junto com o usuário, e o commit fixado em `scripts/install-cobalt.sh` sobe quando a
correção entra. A variante com whatsmeow (Go) vive em outro repositório
(`jonales/msgforge-whatsmeow`); o spike comparativo está na ADR 0009. Roteiro ao vivo:
`docs/VALIDACAO-COBALT-FORK.md`. Na nuvem não dá para testar ao vivo (o proxy bloqueia
`web.whatsapp.com`).

Problemas conhecidos (mantenha esta lista atual; ao corrigir um item, tire-o daqui no mesmo PR):
1. `mentioned`, `quoted`, `linkPreview` e `webhookBase64` são aceitos e ignorados; `delay` só
   funciona no Cobalt ("digitando..."). Mídia recebida vai no `messages.upsert` sem download/base64.
   Figurinha, botões e listas ainda não existem.
2. O RabbitMQ usa um exchange tópico, e não filas por evento (`docs/COMPATIBILIDADE.md`).
3. `PrivateNetworkGuard` não cobre DNS rebinding (resolve antes do connect). Vem desligado por
   padrão.
4. **Cobalt ao vivo** (rodada de 2026-11-12/13 com dois números): tudo o que o teste ao vivo achou
   foi corrigido no motor (resolve de 30 s, restart pedindo QR, ack 479 em conta Business, sessão
   Signal, aparelho vinculado, mídia e áudio, fila offline, aviso de código de segurança no Web,
   nome do perfil sobrescrito pelo do aparelho ao restaurar, retry e exceção solta no login). O
   retry (`receipt type=retry`) segue o `getTargetChat` do WhatsApp Web, mas ainda não foi
   observado ao vivo. A suíte do motor tem 16 falhas e 93 erros que já existem no `master`.
5. Histórico (ADR 0008): o que chegou antes de a instância receber eventos não aparece. A
   retenção (`HISTORY_RETENTION_DAYS`) vem desligada por padrão.
6. `/instance/create`: `alwaysOnline` é aceito e guardado, mas **ainda sem efeito** (o motor
   anuncia "disponível" no login e nada manda "indisponível"). `rejectCall` foi validado ao vivo
   (2026-11-14); `msgCall` e a leitura automática (`readMessages`/`readStatus`) ainda não. O proxy não é testado no
   create (a referência devolve 400 "Invalid proxy").
7. Eventos além das mensagens (`MESSAGES_SET`, `MESSAGES_DELETE`, `CONTACTS_*`, `PRESENCE_UPDATE`,
   `CHATS_*`, `GROUPS_*`/`GROUP_*`, `CALL`) ainda não foram testados ao vivo no Cobalt. Limites:
   `CHATS_UPSERT`/`CONTACTS_UPSERT` repetem a lista inteira depois de reiniciar o processo (o
   filtro é por conexão, em memória); `profilePicUrl` sempre null; `MESSAGES_SET` sem `isLatest` e
   `progress`; sem `groups.update` para troca de foto; os eventos de grupo das operações feitas pela
   própria API dependem de o WhatsApp mandar a notificação também ao aparelho que agiu.

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
