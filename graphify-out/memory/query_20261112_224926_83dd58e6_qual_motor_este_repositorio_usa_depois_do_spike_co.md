---
type: "query"
date: "2026-11-12T22:49:26.909713+00:00"
question: "Qual motor este repositorio usa depois do spike comparativo"
contributor: "graphify"
outcome: "corrected"
correction: "A recomendacao de trocar o motor por whatsmeow neste repositorio nao vale: o usuario decidiu manter o fork do Cobalt aqui e levar o Go para outro repositorio (2026-11-12)."
source_nodes: ["WhatsAppGateway", "CobaltWhatsAppGateway"]
---

# Q: Qual motor este repositorio usa depois do spike comparativo

## Answer

O msgforge-api segue com o Cobalt 0.1.0 do fork jonales/Cobalt (ADR 0007 + ADR 0009). A variante whatsmeow (Go) vive em outro repositorio, jonales/msgforge-whatsmeow. Os defeitos do 0.1.0 vistos no spike (aviso de seguranca no WhatsApp Web, nao decifrar mensagem de aparelho vinculado, recibos que nao disparam, bugs do builder) sao corrigidos no fork e entram subindo o COBALT_REF de scripts/install-cobalt.sh.

## Outcome

- Signal: corrected
- Correction: A recomendacao de trocar o motor por whatsmeow neste repositorio nao vale: o usuario decidiu manter o fork do Cobalt aqui e levar o Go para outro repositorio (2026-11-12).

## Source Nodes

- WhatsAppGateway
- CobaltWhatsAppGateway