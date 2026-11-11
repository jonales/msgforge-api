---
type: "query"
date: "2026-11-11T15:30:56.969279+00:00"
question: "Como testar o CobaltWhatsAppGateway sem falar com o WhatsApp?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CobaltWhatsAppGateway", "CobaltMessageMapper"]
---

# Q: Como testar o CobaltWhatsAppGateway sem falar com o WhatsApp?

## Answer

COBALT_PROXY (OptionsBuilder.proxy) apontando para uma porta fechada (ex.: http://127.0.0.1:9): connect() falha em cerca de 200 ms com ConnectException em qualquer maquina, inclusive no CI, que tem internet. CobaltWhatsAppGatewayTest (unitario, InMemorySessionStore + events::add) e CobaltGatewayIT (Spring, msgforge.gateway=cobalt). Mensagens para o mapper sao montadas com ChatMessageInfoBuilder/ChatMessageKeyBuilder. Video de teste: src/test/resources/media/tiny.mp4 (bytes invalidos fazem o ffprobe esperar cerca de 10 s).

## Outcome

- Signal: useful

## Source Nodes

- CobaltWhatsAppGateway
- CobaltMessageMapper