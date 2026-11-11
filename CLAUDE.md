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

- `instance/`, `message/`, `chat/`, `webhook/`: controllers e DTOs (records) no formato JSON do contrato.
- `gateway/`: `WhatsAppGateway` é a **única** fronteira com o protocolo. Dois motores:
  `StubWhatsAppGateway` (`MSGFORGE_GATEWAY=stub`, padrão) e `gateway/cobalt/CobaltWhatsAppGateway`
  (`MSGFORGE_GATEWAY=cobalt`, ADR 0007). Nada fora de `gateway/cobalt/` pode importar classes do
  Cobalt (`it.auties.*`).
  - `CobaltMessageMapper`: mensagens do Cobalt → JSON Baileys (`messages.upsert`/`update`).
  - `DatabaseSessionSerializer`: sessão do Cobalt (Keys + Store) no `session_store`, num blob só
    (`CobaltSessionCodec`), com lock por instância.
  - `src/main/java/com/aspose/words/` é um **shim** obrigatório (o `aspose-words` é comercial e
    foi excluído). Sem ele, montar mídia quebra com `NoClassDefFoundError`. Não apague.
  - Testes do Cobalt nunca falam com o WhatsApp: `COBALT_PROXY` aponta para uma porta fechada
    (`CobaltWhatsAppGatewayTest`, `CobaltGatewayIT`). Para manter um connect **pendente**, use
    um proxy "mudo" (aceita e não responde), como em `concurrentConnectsShareOnePendingConnection`.
  - `connect`/`logout` são serializados por instância (lock): nunca dois clientes do WhatsApp para
    a mesma sessão. Um `connect` com QR já pendente **reaproveita** a conexão e devolve o QR atual;
    pedir pairing code recomeça.
- Envios além de texto/mídia passam por `WhatsAppGateway.send(Instance, Outgoing)`, com
  `Outgoing` **selado** (VoiceNote, Location, Contacts, Reaction, Poll): um tipo novo obriga stub e
  Cobalt a tratá-lo. vCard é montado em `message/VCards` (com escape, contra injeção de campos).
  Enquete no Cobalt usa o builder completo: o `simpleBuilder` ignora o `selectableCount`.
- `/chat/*` (`ChatService`) usa `checkNumbers`, `markRead`, `sendPresence`, `fetchProfile`,
  `profilePictureUrl` e `updateProfile` (`ProfileUpdate` selado). Foto e recado escondidos pela
  privacidade viram null, não erro. `markRead` de mensagem fora do Store do Cobalt marca a conversa
  inteira.
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
  `/{grupo}/{acao}/{instancia}`.
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
- Próximas ideias (não planejadas): webhook global, envio de mídia, ver as últimas mensagens.

## Estado atual e backlog

O roadmap está no `README.md`. As Fases 1.5 e 1.6 terminaram. **Fase 2:** o
`CobaltWhatsAppGateway` está implementado e testado **offline** (ADR 0007 ainda *proposta*).
**Nunca foi validado ao vivo**, porque o proxy da nuvem bloqueia `web.whatsapp.com`. O roteiro
para validar com um número de teste está em `docs/spikes/cobalt/README.md`. Se o protocolo da
0.0.10 não funcionar, a alternativa preferida é um sidecar com Baileys (só troca `gateway/`).

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
5. **Cobalt sem teste ao vivo** (ver acima). Pontos frágeis a observar no primeiro teste: o
   `profileName` vem do `Store.name()` (pode vir o nome do aparelho; nesse caso vai nulo); texto
   recebido sai sempre como `conversation` (nunca `extendedTextMessage`); a reação manda o
   timestamp em milissegundos num campo que o Cobalt chama de `timestampSeconds`.
6. A **API** não limita tentativas de chave errada; o Manager sim.

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
