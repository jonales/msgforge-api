---
type: "query"
date: "2026-11-12T12:05:09.700311+00:00"
question: "como chat e grupos seguem o resolve e o LID do teste ao vivo"
contributor: "graphify"
outcome: "corrected"
correction: "nunca chamar hasWhatsapp sem UsersCache.forget nem toSimpleJid em JID de participante"
source_nodes: ["CobaltWhatsAppGateway", "LidResolver", "UsersCache"]
---

# Q: como chat e grupos seguem o resolve e o LID do teste ao vivo

## Answer

Depois do teste ao vivo (main 7431f04), todo numero vai por resolve (UsersCache.forget + hasWhatsapp + JID devolvido). As rotas do PR 9 seguiam o caminho antigo: checkNumbers e fetchProfile chamavam hasWhatsapp sem limpar o cache envenenado, e grupos usavam toSimpleJid em LID (vira telefone falso). Corrigido: resolveMany (lote) em checkNumbers e participantes, resolveOrNull em perfil e foto, resolve em sendPresence, groupInfo recebe connection.lids, updateParticipants devolve os JIDs pedidos.

## Outcome

- Signal: corrected
- Correction: nunca chamar hasWhatsapp sem UsersCache.forget nem toSimpleJid em JID de participante

## Source Nodes

- CobaltWhatsAppGateway
- LidResolver
- UsersCache