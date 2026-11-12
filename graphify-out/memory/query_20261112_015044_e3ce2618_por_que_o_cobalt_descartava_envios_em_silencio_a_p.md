---
type: "query"
date: "2026-11-12T01:50:44.695987+00:00"
question: "Por que o Cobalt descartava envios em silencio a partir do segundo"
contributor: "graphify"
outcome: "corrected"
correction: "Nunca confiar no future do sendMessage do Cobalt: conferir status ERROR e resolver o JID antes."
source_nodes: ["CobaltWhatsAppGateway", "UsersCache"]
---

# Q: Por que o Cobalt descartava envios em silencio a partir do segundo

## Answer

Whatsapp.sendMessage chama prepareChat -> hasWhatsapp, que usa o cache estatico usersCache. parseHasWhatsappResponse guarda o resultado sob o JID devolvido pelo servidor e marca false o JID consultado quando diferem (ex.: numero BR com 9 extra resolve sem ele). Com false no cache, sendMessage marca a mensagem com status ERROR e conclui o future normalmente, sem mandar nada. Correcao: CobaltWhatsAppGateway.resolve (UsersCache.forget + hasWhatsapp, usa o JID devolvido; sem JID -> 400 exists:false) e deliver (confere status ERROR).

## Outcome

- Signal: corrected
- Correction: Nunca confiar no future do sendMessage do Cobalt: conferir status ERROR e resolver o JID antes.

## Source Nodes

- CobaltWhatsAppGateway
- UsersCache