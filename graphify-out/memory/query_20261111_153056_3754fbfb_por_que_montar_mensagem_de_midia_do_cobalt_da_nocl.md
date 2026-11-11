---
type: "query"
date: "2026-11-11T15:30:56.882142+00:00"
question: "Por que montar mensagem de midia do Cobalt da NoClassDefFoundError com aspose?"
contributor: "graphify"
outcome: "corrected"
correction: "Excluir aspose-words exige o shim com.aspose.words; testar montagem de midia, nao so texto."
source_nodes: ["CobaltWhatsAppGateway"]
---

# Q: Por que montar mensagem de midia do Cobalt da NoClassDefFoundError com aspose?

## Answer

A classe it.auties.whatsapp.util.Medias referencia Document/SaveOptions/ImageSaveOptions/SaveOutputParameters de com.aspose.words; a JVM carrega esses tipos ao verificar Medias, entao o catch(Throwable) dos metodos nao ajuda e qualquer ImageMessageSimpleBuilder.build() falha sem o aspose-words. Solucao: shim em src/main/java/com/aspose/words (classes vazias que lancam UnsupportedOperationException). O spike so testou texto e chaves e concluiu errado que a ausencia so degradava.

## Outcome

- Signal: corrected
- Correction: Excluir aspose-words exige o shim com.aspose.words; testar montagem de midia, nao so texto.

## Source Nodes

- CobaltWhatsAppGateway