---
type: "query"
date: "2026-11-11T13:48:02.296601+00:00"
question: "Por que a aplicação não subia com 'RestClient$Builder could not be found'?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["HttpClientConfig"]
---

# Q: Por que a aplicação não subia com 'RestClient$Builder could not be found'?

## Answer

No Spring Boot 4 o auto-config do RestClient.Builder saiu do starter web para o spring-boot-starter-restclient. Corrigido adicionando a dependência no pom.xml. Regra geral no Boot 4: um bean de auto-config que sumiu costuma ser um starter modular faltando.

## Outcome

- Signal: useful

## Source Nodes

- HttpClientConfig