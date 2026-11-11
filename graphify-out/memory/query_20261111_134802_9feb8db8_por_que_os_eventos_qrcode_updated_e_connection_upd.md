---
type: "query"
date: "2026-11-11T13:48:02.384215+00:00"
question: "Por que os eventos qrcode.updated e connection.update do instance/create com qrcode=true não chegam ao webhook?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["EventDispatcher", "InstanceService", "GatewayEvent"]
---

# Q: Por que os eventos qrcode.updated e connection.update do instance/create com qrcode=true não chegam ao webhook?

## Answer

EventDispatcher.on submete deliver() a uma virtual thread, que faz instances.findByName e webhooks.findEntity antes do commit da transação de InstanceService.create; a instância ainda não existe para outra conexão e o evento é descartado. Reproduzido em 2026-11-11 com um receptor HTTP local. Correção planejada: @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true) e/ou snapshot de token/ownerJid/webhook dentro do GatewayEvent.

## Outcome

- Signal: useful

## Source Nodes

- EventDispatcher
- InstanceService
- GatewayEvent