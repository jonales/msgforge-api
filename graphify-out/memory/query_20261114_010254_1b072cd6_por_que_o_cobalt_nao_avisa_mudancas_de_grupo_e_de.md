---
type: "query"
date: "2026-11-14T01:02:54.725436+00:00"
question: "Por que o Cobalt nao avisa mudancas de grupo e de onde saem os eventos GROUP"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CobaltEvents", "CobaltWhatsAppGateway"]
---

# Q: Por que o Cobalt nao avisa mudancas de grupo e de onde saem os eventos GROUP

## Answer

O NotificationGroupStreamHandler do cobalt-2 aplica a notificacao w:gp2 no Store mas nao chama listener; onGroups so dispara no queryParticipatingGroups (fetchAllGroups). Os eventos GROUPS_UPSERT/GROUP_UPDATE/GROUP_PARTICIPANTS_UPDATE saem do no recebido via addNodeReceivedListener em CobaltEvents.groupNotification. O linked client tambem nunca chama onMessageDeleted: o REVOKE chega como ProtocolMessage no onNewMessage.

## Outcome

- Signal: useful

## Source Nodes

- CobaltEvents
- CobaltWhatsAppGateway