---
type: "query"
date: "2026-11-11T15:30:57.053589+00:00"
question: "Por que o InstanceService nao usa Transactional em create e connect?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["InstanceService", "EventDispatcher"]
---

# Q: Por que o InstanceService nao usa Transactional em create e connect?

## Answer

Com o Cobalt, QR e connection.update chegam por threads do motor; o EventDispatcher faz o snapshot da instancia nessas threads, entao a instancia precisa estar commitada (senao o evento e descartado, o mesmo bug da Fase 1.5). E connect pode esperar ate COBALT_QR_WAIT_MS pelo QR; segurar transacao prenderia conexao do pool. create persiste com TransactionTemplate e conecta depois do commit; o estado vem dos eventos do gateway, connect nao forca 'connecting'.

## Outcome

- Signal: useful

## Source Nodes

- InstanceService
- EventDispatcher