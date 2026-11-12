---
type: "query"
date: "2026-11-12T13:22:33.624413+00:00"
question: "o que o cobalt 0.1.0 do fork resolve e quais defeitos tem"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CobaltWhatsAppGateway"]
---

# Q: o que o cobalt 0.1.0 do fork resolve e quais defeitos tem

## Answer

Fork jonales/Cobalt = upstream master 0.1.0 (reescrita, 13 modulos JPMS, chamadas bloqueantes). Resolve nativamente LID<->PN, versao do WA Web, fila offline, receipt retry, sem cache estatico de usuarios. Defeitos corrigidos no fork (PR jonales/Cobalt#1): Options.Web.name() devolvia Mobile; QrCode.toTerminal() carregava libfido2 no static init do builder (quebra em container); disconnect apos connect falho esperava 30s pelo sync de AB props e trocava o erro por SyncTimeout. Sessao padrao vai para H2 MVStore em disco; LinkedWhatsAppStoreFactory e non-sealed (da para guardar no Postgres). Teste ao vivo em docs/spikes/cobalt-0.1.

## Outcome

- Signal: useful

## Source Nodes

- CobaltWhatsAppGateway