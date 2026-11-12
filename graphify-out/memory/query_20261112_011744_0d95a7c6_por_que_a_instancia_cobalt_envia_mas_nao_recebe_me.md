---
type: "query"
date: "2026-11-12T01:17:44.473418+00:00"
question: "Por que a instancia Cobalt envia mas nao recebe mensagens"
contributor: "graphify"
outcome: "corrected"
correction: "Nao confiar no Cobalt 0.0.10 para a fila offline; manter o OfflineQueueGuard e mandar o ib sem id."
source_nodes: ["OfflineQueueGuard", "RawNodes", "CobaltWhatsAppGateway"]
---

# Q: Por que a instancia Cobalt envia mas nao recebe mensagens

## Answer

Duas lacunas do Cobalt 0.0.10 na fila offline: (1) nao responde ao ib/offline_preview com ib[offline_batch count=100], entao o servidor entrega so o primeiro lote e segura as mensagens novas; (2) o decoder quebra em mensagens de sync do celular (NPE protocolType/title cannot be null) e nao manda ack, e o servidor reenvia a cada login. OfflineQueueGuard pede o lote e manda o ack que faltou apos 15 s. O ib precisa sair SEM id: Whatsapp.sendNode acrescenta id e o servidor ignora; RawNodes usa SocketHandler.sendNodeWithNoResponse por reflexao. Diagnostico: LOGGING_LEVEL_DEV_MSGFORGE_GATEWAY_COBALT_WIRE=DEBUG mostra os nos (so atributos).

## Outcome

- Signal: corrected
- Correction: Nao confiar no Cobalt 0.0.10 para a fila offline; manter o OfflineQueueGuard e mandar o ib sem id.

## Source Nodes

- OfflineQueueGuard
- RawNodes
- CobaltWhatsAppGateway