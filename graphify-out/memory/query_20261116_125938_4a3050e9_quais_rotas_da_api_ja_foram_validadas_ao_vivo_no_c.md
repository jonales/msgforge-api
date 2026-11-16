---
type: "query"
date: "2026-11-16T12:59:38.729149+00:00"
question: "quais rotas da api ja foram validadas ao vivo no cobalt e quais faltam"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CobaltWhatsAppGateway", "CobaltMessageMapper", "ChatController", "GroupController", "MessageController", "SettingsController"]
---

# Q: quais rotas da api ja foram validadas ao vivo no cobalt e quais faltam

## Answer

Levantamento de 2026-11-16 (sem teste novo): validadas ao vivo create, connect QR, logout, sendText com delay, sendWhatsAppAudio, fetchProfile, updateProfilePicture, grupo (create, findGroupInfos, participants, updateParticipant, subject, description, picture, updateSetting), webhook e os efeitos de rejectCall/msgCall/groupsIgnore/readMessages/readStatus. Em parte: sendMedia (falta document e video que transcodifica), sendReaction (falta remover), sendPresence (fix #48 sem reteste), markMessageAsRead e whatsappNumbers (so indiretos), settings/set com conexao aberta e syncFullHistory true. Sem registro: proxy por instancia (nunca), pairing code, delete, sendLocation, sendContact, sendPoll (voto de enquete nao e mapeado no CobaltMessageMapper), fetchProfilePictureUrl, updateProfileName, updateProfileStatus, fetchAllGroups, inviteCode, revokeInviteCode, acceptInviteCode, leaveGroup, grupos com LID. Tabela e roteiro com curl em docs/VALIDACAO-COBALT-FORK.md (Cobertura das rotas e Proxima rodada).

## Outcome

- Signal: useful

## Source Nodes

- CobaltWhatsAppGateway
- CobaltMessageMapper
- ChatController
- GroupController
- MessageController
- SettingsController