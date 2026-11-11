---
type: "query"
date: "2026-11-11T20:27:46.632888+00:00"
question: "como funcionam os grupos no stub e no cobalt"
contributor: "graphify"
outcome: "useful"
source_nodes: ["GroupService", "StubWhatsAppGateway", "CobaltWhatsAppGateway"]
---

# Q: como funcionam os grupos no stub e no cobalt

## Answer

GroupService valida groupJid (query string, precisa terminar em @g.us) e participantes (s.whatsapp.net ou lid) e o gateway recebe JIDs prontos. O stub guarda os grupos em memoria por instancia (chave nome|jid), o que permite ao GroupIT testar criar, consultar, alterar, convidar e sair. No Cobalt nao ha consulta de todos os grupos: fetchAllGroups usa os chats do Store e chama queryGroupMetadata em paralelo, descartando falhas. ChatRole FOUNDER vira superadmin, ADMIN vira admin; announce e restrict vem das politicas SEND_MESSAGES e EDIT_GROUP_INFO.

## Outcome

- Signal: useful

## Source Nodes

- GroupService
- StubWhatsAppGateway
- CobaltWhatsAppGateway