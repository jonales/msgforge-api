---
type: "query"
date: "2026-11-12T00:10:29.060990+00:00"
question: "por que whatsappNumbers trata o nono digito"
contributor: "graphify"
outcome: "corrected"
correction: "checkNumbers devolve Map<consultado, jid real ou null>"
source_nodes: ["CobaltWhatsAppGateway", "Jids", "ChatService"]
---

# Q: por que whatsappNumbers trata o nono digito

## Answer

O hasWhatsapp do Cobalt chaveia o mapa pelo JID que o WhatsApp responde, nao pelo consultado. Celular brasileiro antigo responde sem o 9, entao procurar pelo JID consultado dava exists false. CobaltWhatsAppGateway.resolveNumbers tenta Jids.brazilianMobileAlternative quando o consultado nao aparece, e a resposta usa o JID real. Envios ainda usam o JID como veio (ponto fragil para o teste ao vivo).

## Outcome

- Signal: corrected
- Correction: checkNumbers devolve Map<consultado, jid real ou null>

## Source Nodes

- CobaltWhatsAppGateway
- Jids
- ChatService