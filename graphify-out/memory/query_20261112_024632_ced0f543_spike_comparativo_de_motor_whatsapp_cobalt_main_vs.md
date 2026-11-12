---
type: "query"
date: "2026-11-12T02:46:32.648637+00:00"
question: "Spike comparativo de motor WhatsApp Cobalt main vs whatsmeow"
contributor: "graphify"
outcome: "useful"
source_nodes: ["WhatsAppGateway", "CobaltWhatsAppGateway"]
---

# Q: Spike comparativo de motor WhatsApp Cobalt main vs whatsmeow

## Answer

Teste ao vivo 2026-11-12 com os mesmos numeros. Cobalt 0.1.0 (main, so no Git; cobalt-lib nao publicado): aviso de codigo de seguranca some no celular mas continua no WhatsApp Web; nao decifra mensagens do Web (sessao aberta por PN, resposta chega por LID: NoSession); listener de status nao dispara; builder com bugs (Options.Web.name faz cast para Mobile, registered passa handler null); busca de versao web quebrada (fixar clientVersion). whatsmeow (Go, commit 2026-11-10, exige Go 1.26): sem aviso no celular nem no Web; recebe do Web com senderAlt (numero) e pushName; envia por LID; recibos de entrega e leitura viram eventos; restart sem QR em ~2 s. Decisao recomendada: motor externo com whatsmeow (opcao B).

## Outcome

- Signal: useful

## Source Nodes

- WhatsAppGateway
- CobaltWhatsAppGateway