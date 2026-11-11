---
type: "query"
date: "2026-11-11T14:00:31.352965+00:00"
question: "Como rodar os testes de integração (*IT) sem Docker?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["IntegrationTest", "TestInfrastructure"]
---

# Q: Como rodar os testes de integração (*IT) sem Docker?

## Answer

TestInfrastructure usa IT_DATABASE_URL (+ IT_DATABASE_USER/PASSWORD, IT_REDIS_HOST/PORT) se definida; senão Testcontainers se houver Docker; senão os ITs são desabilitados via @EnabledIf. Localmente: Postgres 16 (pg_ctl) + redis-server, banco msgforge_it, IT_DATABASE_URL=jdbc:postgresql://localhost:5432/msgforge_it mvn verify. Use nomes únicos (uniqueName), porque o cache de estado no Redis sobrevive ao TRUNCATE.

## Outcome

- Signal: useful

## Source Nodes

- IntegrationTest
- TestInfrastructure