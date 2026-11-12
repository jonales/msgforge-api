---
type: "query"
date: "2026-11-12T00:56:02.916875+00:00"
question: "Por que a sessao do Cobalt nao era restaurada no boot Range out of bounds"
contributor: "graphify"
outcome: "corrected"
correction: "Nunca usar KeysSpec/StoreSpec.decode(byte[]) no Cobalt 0.0.10; usar o decode por ProtobufInputStream.fromStream."
source_nodes: ["DatabaseSessionSerializer", "CobaltWhatsAppGateway"]
---

# Q: Por que a sessao do Cobalt nao era restaurada no boot Range out of bounds

## Answer

Bug do Cobalt 0.0.10: Jid.of(ProtobufString.Lazy) le o JID direto do buffer e passa para JidServer.of um tamanho que mistura offset absoluto com tamanho relativo; so funciona com offset 0. KeysSpec/StoreSpec.decode(byte[]) quebram em qualquer sessao logada (JIDs no meio do protobuf). Contorno no DatabaseSessionSerializer: decodificar com ProtobufInputStream.fromStream(new ByteArrayInputStream(bytes)), que da a cada string um array proprio. Teste: readsBackSessionWithJidsAfterLogin. Ainda: o login (novo ou restaurado) so completa ~60 s depois, apos um iq timeout (erro em LOGIN: Node timed out).

## Outcome

- Signal: corrected
- Correction: Nunca usar KeysSpec/StoreSpec.decode(byte[]) no Cobalt 0.0.10; usar o decode por ProtobufInputStream.fromStream.

## Source Nodes

- DatabaseSessionSerializer
- CobaltWhatsAppGateway