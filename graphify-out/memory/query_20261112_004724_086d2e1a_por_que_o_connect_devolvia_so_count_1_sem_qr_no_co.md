---
type: "query"
date: "2026-11-12T00:47:24.969700+00:00"
question: "Por que o connect devolvia so count 1 sem QR no Cobalt ao vivo"
contributor: "graphify"
outcome: "corrected"
correction: "Nunca usar api.isConnected() do Cobalt como 'logado'; usar o flag loggedIn da Connection."
source_nodes: ["CobaltWhatsAppGateway"]
---

# Q: Por que o connect devolvia so count 1 sem QR no Cobalt ao vivo

## Answer

No Cobalt 0.0.10, Whatsapp.isConnected() fica true logo apos o handshake do socket (SocketState HANDSHAKING->CONNECTED), antes do scan do QR. O gateway usava isso para 'ja conectada' e devolvia NO_CODE; o envio tambem passava. Agora a Connection guarda loggedIn (true no LoggedInListener, false no onDisconnected). Testes offline nao pegam porque o socket nunca abre.

## Outcome

- Signal: corrected
- Correction: Nunca usar api.isConnected() do Cobalt como 'logado'; usar o flag loggedIn da Connection.

## Source Nodes

- CobaltWhatsAppGateway