---
type: "query"
date: "2026-11-11T19:56:30.954865+00:00"
question: "Como funcionam os envios de localizacao, contato, reacao, enquete e audio de voz?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["CobaltWhatsAppGateway", "MessageService", "VCards"]
---

# Q: Como funcionam os envios de localizacao, contato, reacao, enquete e audio de voz?

## Answer

Rotas /message/sendLocation|sendContact|sendReaction|sendPoll|sendWhatsAppAudio -> MessageService -> WhatsAppGateway.send(Instance, Outgoing), Outgoing selado (VoiceNote, Location, Contacts, Reaction, Poll). vCard em message/VCards com escape RFC 6350 (teste de injecao de TEL). Cobalt: LocationMessageBuilder, ContactMessage (1) ou ContactsMessage (varios) com ProtobufString.wrap(vcard), ReactionMessageBuilder com ChatMessageKey, enquete pelo PollCreationMessageBuilder completo (chave aleatoria de 32 bytes, selectableOptionsMap por sha256 do nome) porque o simpleBuilder forca selectableCount = numero de opcoes. Figurinha nao tem builder simples no Cobalt 0.0.10.

## Outcome

- Signal: useful

## Source Nodes

- CobaltWhatsAppGateway
- MessageService
- VCards