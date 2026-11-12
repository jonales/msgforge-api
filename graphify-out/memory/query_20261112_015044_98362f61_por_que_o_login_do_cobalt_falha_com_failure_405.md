---
type: "query"
date: "2026-11-12T01:50:44.734300+00:00"
question: "Por que o login do Cobalt falha com failure 405"
contributor: "graphify"
outcome: "useful"
source_nodes: ["WebVersion", "CobaltWhatsAppGateway"]
---

# Q: Por que o login do Cobalt falha com failure 405

## Answer

O Cobalt 0.0.10 busca client_revision na pagina de web.whatsapp.com, que nao traz mais o valor; cai na versao fixa 2.3000.1022032575 e o servidor recusa (failure reason=405). A revisao atual esta em web.whatsapp.com/sw.js. WebVersion le o sw.js (ou COBALT_WEB_VERSION) e grava em AppMetadata.webVersion por reflexao antes de cada conexao.

## Outcome

- Signal: useful

## Source Nodes

- WebVersion
- CobaltWhatsAppGateway