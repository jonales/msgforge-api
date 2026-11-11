---
type: "query"
date: "2026-11-11T14:50:18.397009+00:00"
question: "O Cobalt 0.0.10 funciona como motor do WhatsApp no Java 25?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["WhatsAppGateway", "StubWhatsAppGateway", "SessionStore"]
---

# Q: O Cobalt 0.0.10 funciona como motor do WhatsApp no Java 25?

## Answer

Offline sim (spike em docs/spikes/cobalt, ADR 0007): bytecode Java 21 roda no 25; chaves geradas; KeysSpec/StoreSpec encode/decode para byte[] com ida e volta identica; cliente monta com Whatsapp.webBuilder().serializer(..).newConnection(UUID); connect() sem rede falha em menos de 1 s com IOException. Exigencias: excluir com.aspose:aspose-words (comercial, fora do Central; usado so para paginas/miniatura de documento, cai no padrao) e org.slf4j:slf4j-nop. Nao verificado: protocolo ao vivo (proxy da nuvem bloqueia web.whatsapp.com); o LiveProbe roda na maquina do usuario.

## Outcome

- Signal: useful

## Source Nodes

- WhatsAppGateway
- StubWhatsAppGateway
- SessionStore