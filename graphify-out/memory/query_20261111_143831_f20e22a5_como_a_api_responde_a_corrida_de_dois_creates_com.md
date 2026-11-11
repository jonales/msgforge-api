---
type: "query"
date: "2026-11-11T14:38:31.759756+00:00"
question: "Como a API responde a corrida de dois creates com o mesmo nome ou token?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["ApiExceptionHandler", "InstanceService"]
---

# Q: Como a API responde a corrida de dois creates com o mesmo nome ou token?

## Answer

A checagem existsBy* do InstanceService pode passar nas duas requisicoes; a segunda bate na constraint (instance_name_key / uk_instance_token) e o ApiExceptionHandler.constraint mapeia DataIntegrityViolationException para 400 pela constraint, sem vazar SQL. Coberto por InstanceFlowIT.concurrentCreatesWithTheSameNameNeverAnswer500 (o log do teste mostra a violacao real).

## Outcome

- Signal: useful

## Source Nodes

- ApiExceptionHandler
- InstanceService