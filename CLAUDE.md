# Msgforge API: memória do projeto

API REST de WhatsApp em Java 25 + Spring Boot 4.1, **compatível com a Evolution API v2**: mesmas
rotas, header `apikey`, formato de erro e envelope de webhook. Um cliente da Evolution deve
conseguir trocar só a URL. Antes de mudar qualquer contrato, confira `docs/COMPATIBILIDADE.md`.

## Comandos

```bash
mvn test                                     # testes (precisa de JDK 25)
mvn spring-boot:run                          # sobe local (Postgres + Redis em localhost)
docker compose up -d --build                 # stack completa
scripts/new-migration.sh "descricao"         # nova migration Flyway no padrão
graphify update .                            # atualiza o grafo de código (sem LLM)
```

Nas sessões em nuvem, `.claude/hooks/session-start.sh` instala o JDK 25, baixa as dependências
do Maven e o Graphify, e gera o grafo.

## Arquitetura (pacote `dev.msgforge`)

- `instance/`, `message/`, `webhook/`: controllers e DTOs (records) no formato JSON da Evolution.
- `gateway/`: `WhatsAppGateway` é a **única** fronteira com o protocolo. Hoje existe
  `StubWhatsAppGateway` (`MSGFORGE_GATEWAY=stub`); o `CobaltWhatsAppGateway` virá na Fase 2.
  Nada fora de `gateway/` pode importar classes do motor (Cobalt).
- `event/`: o gateway publica `GatewayEvent` (interface selada) e o `EventDispatcher` converte
  no envelope da Evolution e entrega no webhook da instância, no webhook global e no RabbitMQ.
  Um evento novo exige um `case` novo nos `switch` exaustivos; isso é proposital.
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

O roadmap está no `README.md`. Estamos entre a Fase 1 (contrato + stub) e a Fase 2 (Cobalt).

Problemas conhecidos (análise de 2026-11-11), a corrigir na "Fase 1.5":
1. **Eventos perdidos:** `EventDispatcher.deliver` roda numa virtual thread e lê a instância e o
   webhook do banco antes do commit. No `instance/create` com `qrcode:true`, o `qrcode.updated` e
   o `connection.update` se perdem. Correção: `@TransactionalEventListener(AFTER_COMMIT,
   fallbackExecution = true)` e/ou levar um snapshot (token, ownerJid, webhook) no evento.
2. `Jids.toJid("abc")` gera `@s.whatsapp.net` e a API responde 201; deveria responder 400.
3. JSON malformado, `IllegalArgumentException` e erros genéricos saem fora do formato de erro da
   Evolution.
4. `webhook.events` aceita valores que não existem em `EventType`.
5. O token da instância recebe 401 em `/instance/fetchInstances`; na Evolution ele vê a própria
   instância.
6. O `RestClient` do webhook não tem timeout nem retry e envia o corpo `chunked`.
7. A apikey é comparada com `equals`; o certo é `MessageDigest.isEqual`.
8. A tabela `session_store` não tem entidade; as instâncias `open` não reconectam no boot.
9. Não há teste de contexto (`@SpringBootTest` + Testcontainers) nem CI.

Ao corrigir um item, tire-o daqui no mesmo PR.

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
