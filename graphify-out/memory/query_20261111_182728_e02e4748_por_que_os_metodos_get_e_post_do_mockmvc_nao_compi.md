---
type: "query"
date: "2026-11-11T18:27:28.396938+00:00"
question: "Por que os metodos get e post do MockMvc nao compilam nos testes de integracao?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["IntegrationTest"]
---

# Q: Por que os metodos get e post do MockMvc nao compilam nos testes de integracao?

## Answer

A classe base IntegrationTest tem helpers get(String)/post(String,String) que escondem os imports estaticos de MockMvcRequestBuilders. Em subclasses, use MockMvcRequestBuilders.get/post qualificados (ManagerIT define GET/POST). @AutoConfigureMockMvc no Boot 4 fica em org.springframework.boot.webmvc.test.autoconfigure (starter spring-boot-starter-webmvc-test).

## Outcome

- Signal: useful

## Source Nodes

- IntegrationTest