# Msgforge API: memória do projeto

API REST de WhatsApp em Java 25 + Spring Boot 4.1, **compatível com a Evolution API v2**: mesmas
rotas, header `apikey`, formato de erro e envelope de webhook. Um cliente da Evolution deve
conseguir trocar só a URL. Antes de mudar qualquer contrato, confira `docs/COMPATIBILIDADE.md`.

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

- `instance/`, `message/`, `webhook/`: controllers e DTOs (records) no formato JSON da Evolution.
- `gateway/`: `WhatsAppGateway` é a **única** fronteira com o protocolo. Hoje existe
  `StubWhatsAppGateway` (`MSGFORGE_GATEWAY=stub`); o `CobaltWhatsAppGateway` virá na Fase 2.
  Nada fora de `gateway/` pode importar classes do motor (Cobalt).
- `event/`: o gateway publica `GatewayEvent` (interface selada) e o `EventDispatcher` converte
  no envelope da Evolution e entrega no webhook da instância, no webhook global e no RabbitMQ.
  Um evento novo exige um `case` novo nos `switch` exaustivos; isso é proposital.
  **A entrega roda depois do commit e usa só um snapshot (`DeliveryTarget`)**; a thread de
  entrega nunca lê o banco (ADR 0004). `WebhookSender` cuida de timeout, retry e
  `Content-Length`.
- `gateway/session/SessionStore`: blob da sessão do WhatsApp por instância (`session_store`).
  `SessionRestorer` reconecta no boot quem tem sessão; logout apaga a sessão.
- `security/ApiKeyFilter`: a chave global libera tudo; o token da instância libera só as rotas
  `/{grupo}/{acao}/{instancia}`.
- `common/ApiExceptionHandler`: erros no formato `{status, error, response: {message: [...]}}`.

## Convenções

- **Banco:** só via Flyway. Siga `docs/MIGRATIONS.md` (nome `V{yyyyMMddHHmm}__desc.sql`, nunca
  editar uma migration que já foi para `main`, entidade JPA no mesmo commit). `ddl-auto: validate`.
- **Nomes de rotas, campos e eventos:** iguais aos da Evolution, mesmo quando parecerem estranhos.
  `ContractNamingTest` protege os nomes de eventos e JIDs.
- **Java 25:** records para DTOs, `sealed` + pattern matching, virtual threads
  (`spring.threads.virtual.enabled`). Injeção por construtor, sem Lombok.
- **Spring Boot 4:** Jackson 3 (`tools.jackson.*`; as anotações continuam em
  `com.fasterxml.jackson.annotation`). Os starters são modulares: um auto-config que "sumiu"
  costuma ser um starter faltando (o `spring-boot-starter-restclient`, por exemplo).
- **Commits:** em português, no estilo Conventional Commits (`feat:`, `fix:`, `refactor:`, `docs:`).
- **Decisões de arquitetura:** registre em `docs/decisoes/` (ADR curto, numerado).

## Estado atual e backlog

O roadmap está no `README.md`. A Fase 1.5 (estabilização) terminou; o próximo passo é a
Fase 2 (`CobaltWhatsAppGateway`).

Problemas conhecidos (mantenha esta lista atual; ao corrigir um item, tire-o daqui no mesmo PR):
1. `mentioned`, `quoted`, `delay` e `webhookBase64` são aceitos e ignorados (dependem do gateway real).
2. O RabbitMQ usa um exchange tópico; a Evolution usa filas por evento (`docs/COMPATIBILIDADE.md`).
3. As entregas de webhook ficam só em memória: se o processo cair durante os retries, o evento
   se perde. Uma fila persistente (outbox) resolveria; ainda não é necessário.

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
- O grafo (`graph.json`, `GRAPH_REPORT.md`, `graph.html`) é **gerado** e não é versionado.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- When graphify-out/graph.json exists, run `graphify query "<question>"` before your first grep, search or source-file read for a codebase question, and put this rule in every subagent prompt that explores code. Read raw files only for lines the graph or an earlier read already located. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- If graphify-out/graph.json does not exist (graphify not installed), fall back to normal search.
