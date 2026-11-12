---
type: "query"
date: "2026-11-12T01:50:44.773260+00:00"
question: "Como o Msgforge traduz LID para numero e gera messages.update no Cobalt"
contributor: "graphify"
outcome: "useful"
source_nodes: ["LidResolver", "SentStatusTracker", "CobaltMessageMapper"]
---

# Q: Como o Msgforge traduz LID para numero e gera messages.update no Cobalt

## Answer

LidResolver aprende pares LID->numero dos atributos sender_pn/participant_pn/peer_recipient_pn dos nos message (e do Store.lid/jid da conta); LID sem traducao sai como <lid>@lid. SentStatusTracker registra os ids enviados e converte ack(class=message)->SERVER_ACK, receipt sem tipo->DELIVERY_ACK, read->READ, played->PLAYED, so avancando. LID no Cobalt = agent 1 (ex.: 249533976023044_1:16@s.whatsapp.net); toSimpleJid() descarta o agente.

## Outcome

- Signal: useful

## Source Nodes

- LidResolver
- SentStatusTracker
- CobaltMessageMapper