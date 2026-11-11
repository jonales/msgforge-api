---
type: "query"
date: "2026-11-11T14:38:31.583920+00:00"
question: "Como garantir a ordem dos eventos de webhook por instancia?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["OrderedExecutor", "EventDispatcher"]
---

# Q: Como garantir a ordem dos eventos de webhook por instancia?

## Answer

OrderedExecutor (ADR 0006): uma fila por instancia drenada por uma virtual thread por vez; filas vazias saem do mapa (flag retired evita a corrida entre computeIfAbsent e remocao); teto WEBHOOK_MAX_PENDING_PER_INSTANCE. Tudo de um evento (webhook da instancia, global, RabbitMQ) vai na mesma tarefa. Custo: bloqueio na cabeca da fila por instancia quando o destino esta lento.

## Outcome

- Signal: useful

## Source Nodes

- OrderedExecutor
- EventDispatcher