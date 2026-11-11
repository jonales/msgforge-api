---
type: "query"
date: "2026-11-11T14:00:31.135713+00:00"
question: "Como funciona a entrega de eventos (webhook/RabbitMQ) e por que não lê o banco?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["EventDispatcher", "DeliveryTarget", "WebhookSender"]
---

# Q: Como funciona a entrega de eventos (webhook/RabbitMQ) e por que não lê o banco?

## Answer

Corrigido na Fase 1.5 (ADR 0004). EventDispatcher.on roda síncrono na transação de quem publicou: atualiza estado e copia token/ownerJid/webhook para DeliveryTarget. A entrega vai para uma virtual thread em TransactionSynchronization.afterCommit (ou na hora, sem transação) e usa só o snapshot; rollback não entrega nada. Testes de regressão: InstanceFlowIT.createWithQrCode... e deleteStill..., que falham com a implementação antiga.

## Outcome

- Signal: useful

## Source Nodes

- EventDispatcher
- DeliveryTarget
- WebhookSender