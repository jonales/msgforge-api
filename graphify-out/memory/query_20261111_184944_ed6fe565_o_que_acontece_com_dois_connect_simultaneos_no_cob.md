---
type: "query"
date: "2026-11-11T18:49:44.519284+00:00"
question: "O que acontece com dois connect simultaneos no CobaltWhatsAppGateway?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CobaltWhatsAppGateway"]
---

# Q: O que acontece com dois connect simultaneos no CobaltWhatsAppGateway?

## Answer

Antes da revisao: cada chamada criava um cliente do WhatsApp para a mesma sessao (10 chamadas = 10 clientes, provado com proxy mudo). Agora connect/logout usam lock por instancia; um connect com QR pendente reaproveita a Connection (lastCode/firstCode) e devolve o QR atual; pedido com pairing recomeca. Teste: CobaltWhatsAppGatewayTest.concurrentConnectsShareOnePendingConnection (100/100).

## Outcome

- Signal: useful

## Source Nodes

- CobaltWhatsAppGateway