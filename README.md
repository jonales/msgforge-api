# Msgforge API

API REST de WhatsApp em **Java 25 + Spring Boot 4.1**, com os mesmos endpoints, header `apikey`
e payloads de webhook da Evolution API v2 — para trocar uma pela outra mudando só a URL.

> Projeto independente, sem afiliação com WhatsApp/Meta nem com a Evolution Foundation.
> Conexão não oficial (WhatsApp Web) pode levar ao banimento do número; use com responsabilidade.

## Stack

| Papel | Evolution API | Msgforge |
|---|---|---|
| HTTP | Express (TS) | Spring Boot 4.1 (WebMVC + virtual threads) |
| Protocolo WhatsApp | Baileys | Cobalt (Fase 2), atrás de `WhatsAppGateway` |
| Banco | Prisma | Spring Data JPA + Flyway (PostgreSQL) |
| Cache | Redis | Spring Data Redis |
| Fila | RabbitMQ | Spring AMQP (exchange tópico) |
| Docs | Swagger | springdoc em `/docs` |

Recursos do Java 25 usados: virtual threads, records, `sealed interface` + pattern matching
exaustivo nos eventos (`GatewayEvent`), unnamed patterns (`case X _`).

## Rodando

```bash
cp .env.example .env
docker compose up -d --build
curl http://localhost:8080/
```

Sem Docker (precisa de JDK 25, Postgres e Redis locais):

```bash
mvn spring-boot:run
```

Por padrão roda com `MSGFORGE_GATEWAY=stub`: tudo funciona (instâncias, envio, webhooks, RabbitMQ),
mas nada sai para o WhatsApp de verdade. Serve para validar o contrato contra seus sistemas atuais.

Com `MSGFORGE_GATEWAY=cobalt`, a conexão é real (QR ou pairing code, envio, recebimento, status),
pelo Cobalt. **Ainda não foi validada ao vivo**: use um número de teste e siga
[docs/spikes/cobalt](docs/spikes/cobalt/README.md).

## Manager

Abra `http://localhost:8080/manager` e entre com a `AUTHENTICATION_API_KEY`. Dá para criar
instâncias, conectar por QR code ou código de pareamento (atualiza sozinho), configurar o
webhook, mandar uma mensagem de teste, desconectar e excluir.

## Exemplo

```bash
curl -X POST http://localhost:8080/instance/create \
  -H "apikey: troque-esta-chave" -H "Content-Type: application/json" \
  -d '{"instanceName":"vendas","qrcode":true}'

curl -X POST http://localhost:8080/message/sendText/vendas \
  -H "apikey: troque-esta-chave" -H "Content-Type: application/json" \
  -d '{"number":"5561999999999","text":"Olá do Msgforge"}'
```

## Estrutura

```
dev.msgforge
├── instance/   /instance/*  (create, connect, connectionState, fetchInstances, logout, delete)
├── message/    /message/*   (sendText, sendMedia)
├── webhook/    /webhook/*   (set, find)
├── event/      GatewayEvent (selado) → envelope Evolution → webhook / RabbitMQ
├── gateway/    WhatsAppGateway + StubWhatsAppGateway (Cobalt entra aqui)
├── security/   filtro do header apikey (global ou token da instância)
└── config/
```

## Roadmap

- **Fase 1:** contrato da API, persistência, webhooks, RabbitMQ, gateway stub
- **Fase 1.6 (concluída):** entrega ordenada por instância, erros de constraint/Spring no formato da Evolution, bloqueio opcional de rede privada nos webhooks, testes mais robustos
- **Fase 1.5 (concluída):** entrega de eventos após commit, retry de webhook, validações e erros no formato da Evolution, `session_store`, testes de integração e CI
- **Fase 2 (implementada, falta validar ao vivo):** `CobaltWhatsAppGateway` (QR/pairing, sessão em `session_store`, envio de texto e mídia, recebimento, status). [ADR 0007](docs/decisoes/0007-motor-whatsapp-cobalt.md), roteiro de teste em [docs/spikes/cobalt](docs/spikes/cobalt/README.md)
- **Fase 3:** grupos, chat (findMessages, markAsRead), perfil, S3/MinIO para mídia
- **Fase 4:** testes de contrato com a collection Postman da Evolution
- **Manager v1 (concluído):** painel web em `/manager` com Thymeleaf + htmx (login, instâncias,
  QR code ao vivo, webhook, mensagem de teste). [ADR 0005](docs/decisoes/0005-front-manager-thymeleaf.md)

Testes: `mvn test` (unitários) e `mvn verify` (com integração, via Testcontainers).

Detalhe de cada rota em [docs/COMPATIBILIDADE.md](docs/COMPATIBILIDADE.md).

## Manutenção

- **Banco:** migrations Flyway no padrão de [docs/MIGRATIONS.md](docs/MIGRATIONS.md); crie com
  `scripts/new-migration.sh "descricao"`.
- **Decisões de arquitetura:** [docs/decisoes/](docs/decisoes/).
- **Memória para agentes (Claude Code):** [CLAUDE.md](CLAUDE.md), skills em `.claude/skills/` e
  [Graphify](https://github.com/Graphify-Labs/graphify) (`pip install "graphifyy[sql]==0.9.106"`,
  depois `graphify update .`). As memórias versionadas ficam em `graphify-out/memory/`.
