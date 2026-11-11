---
type: "query"
date: "2026-11-11T14:50:18.481305+00:00"
question: "Por que o build quebra ao adicionar o Cobalt 0.0.10?"
contributor: "graphify"
outcome: "corrected"
correction: "Sempre excluir aspose-words e slf4j-nop ao depender do Cobalt 0.0.10."
source_nodes: ["WhatsAppGateway"]
---

# Q: Por que o build quebra ao adicionar o Cobalt 0.0.10?

## Answer

O pom do Cobalt depende de com.aspose:aspose-words:22.11 (jdk17), publicado so em releases.aspose.com (comercial). Sem acesso a esse repositorio o Maven nao resolve. Solucao: <exclusion> de com.aspose:aspose-words (e de org.slf4j:slf4j-nop). Medias.getPagesCount/getDocumentThumbnail capturam Throwable, entao a ausencia so degrada (1 pagina, sem miniatura).

## Outcome

- Signal: corrected
- Correction: Sempre excluir aspose-words e slf4j-nop ao depender do Cobalt 0.0.10.

## Source Nodes

- WhatsAppGateway