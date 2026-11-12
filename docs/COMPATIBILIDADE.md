# Compatibilidade com a Evolution API v2

Legenda: ✅ implementado · 🟡 parcial · ⬜ pendente

## Instance
| Método | Rota | Status |
|---|---|---|
| POST | `/instance/create` | ✅ (instanceName, token, qrcode, number, integration, webhook) |
| GET | `/instance/connect/{instance}?number=` | ✅ (Cobalt: QR + base64; com `number`, pairing code) |
| GET | `/instance/connectionState/{instance}` | ✅ (cache Redis) |
| GET | `/instance/fetchInstances?instanceName=` | ✅ (com token de instância, retorna só a própria) |
| DELETE | `/instance/logout/{instance}` | ✅ |
| DELETE | `/instance/delete/{instance}` | ✅ |
| POST | `/instance/restart/{instance}` | ⬜ |
| POST | `/instance/setPresence/{instance}` | ⬜ |

## Message
| Método | Rota | Status |
|---|---|---|
| POST | `/message/sendText/{instance}` | 🟡 (`delay` = digitando no Cobalt; mentioned/quoted ignorados) |
| POST | `/message/sendMedia/{instance}` | 🟡 (image/video/audio/document por URL ou base64; teto `COBALT_MEDIA_MAX_BYTES`; vídeo precisa ser MP4/3GP/WebM, senão 400) |
| POST | `/message/sendWhatsAppAudio/{instance}` | ✅ envia como voz (PTT); `encoding` (padrão true) converte para OGG/Opus com o ffmpeg; `false` envia como veio |
| POST | `/message/sendLocation/{instance}` | ✅ |
| POST | `/message/sendContact/{instance}` | ✅ (1 contato → `contactMessage`; vários → `contactsArrayMessage`; vCard com escape) |
| POST | `/message/sendReaction/{instance}` | ✅ (`reaction` vazio remove) |
| POST | `/message/sendPoll/{instance}` | ✅ (`selectableCount` respeitado; opções únicas, 2 a 12) |
| POST | `/message/sendButtons`, `sendList`, `sendSticker` | ⬜ (o Cobalt 0.0.10 não tem builder simples de figurinha) |

## Webhook / Eventos
| Item | Status |
|---|---|
| POST `/webhook/set/{instance}`, GET `/webhook/find/{instance}` | ✅ |
| Envelope (`event`, `instance`, `data`, `destination`, `date_time`, `sender`, `server_url`, `apikey`) | ✅ |
| `webhookByEvents` (sufixo `/messages-upsert`) | ✅ |
| Webhook global (`WEBHOOK_GLOBAL_URL`) | ✅ |
| RabbitMQ (routing key `{instancia}.{evento}`) | ✅ |
| Retry de webhook (timeout, backoff exponencial) | ✅ (`WEBHOOK_REQUEST_TIMEOUT_MS`, `WEBHOOK_RETRY_*`) |
| Validação de `events` (nomes da Evolution, qualquer caixa) | ✅ |
| Ordem de entrega por instância (inclusive com retry) | ✅ (`WEBHOOK_MAX_PENDING_PER_INSTANCE`) |
| Bloqueio opcional de webhook para rede interna (SSRF) | ✅ `WEBHOOK_BLOCK_PRIVATE_NETWORKS` (padrão `false`; sem equivalente na Evolution) |
| Eventos emitidos: QRCODE_UPDATED, CONNECTION_UPDATE, SEND_MESSAGE, MESSAGES_UPSERT | ✅ |
| MESSAGES_UPDATE (status SERVER_ACK / DELIVERY_ACK / READ / PLAYED) | 🟡 só com `MSGFORGE_GATEWAY=cobalt` |
| `connection.update` com `wuid` e `profileName` ao conectar; `statusReason` 401/403/428 | 🟡 Cobalt |
| `qrcode.updated` com `base64` (PNG em data URL) | 🟡 Cobalt (o stub não gera imagem) |
| Demais eventos (MESSAGES_UPDATE, CONTACTS_*, CHATS_*, GROUPS_*) | ⬜ Fase 2/3 |

## Manager (painel web)
| Item | Status |
|---|---|
| `/manager`: equivalente ao Evolution Manager, em Thymeleaf (ADR 0005) | ✅ v1 (login, instâncias, QR/pairing ao vivo, webhook, mensagem de teste, desconectar/excluir) |

## Chat e Profile
| Método | Rota | Status |
|---|---|---|
| POST | `/chat/whatsappNumbers/{instance}` | ✅ `[{exists, jid, number}]`, um por número pedido (repetidos também), na ordem; `jid` é o que o WhatsApp conhece (celular brasileiro pode vir sem o nono dígito); número sem formato de telefone (ou grupo) volta `exists:false, jid:null` sem consultar o WhatsApp; até 100 por chamada |
| POST | `/chat/markMessageAsRead/{instance}` | 🟡 Cobalt: mensagem conhecida na sessão é lida uma a uma; desconhecida marca a conversa inteira; `fromMe:true` é ignorada |
| POST | `/chat/sendPresence/{instance}` | 🟡 `{number, presence, delay}` → 201 `{presence}`; `composing`/`recording` com `delay` voltam ao normal depois (teto 20 s); `paused` = `available` no Cobalt |
| POST | `/chat/fetchProfilePictureUrl/{instance}` | ✅ `{wuid, profilePictureUrl}` (null se não houver ou a privacidade esconder) |
| POST | `/chat/fetchProfile/{instance}` | 🟡 `{wuid, name, numberExists, picture, status}`; sem `number`, a própria conta; `name` só de contatos da sessão; sem dados de conta comercial |
| POST | `/chat/updateProfileName`, `updateProfileStatus`, `updateProfilePicture` | ✅ `{update: "success"}`; foto só JPEG/PNG |
| POST | `/chat/findMessages/{instance}` | 🟡 `{messages: {total, pages, currentPage, records}}`; filtro só por `where.key` (`remoteJid`, `fromMe`, `id`); `offset` = tamanho da página (padrão 50, até 500); mais novas primeiro. Só o que chegou depois que a instância passou a receber eventos (ADR 0008) |
| POST | `/chat/findChats/{instance}` | 🟡 `[{remoteJid, pushName, updatedAt, lastMessage}]` montado do histórico; sem `unreadCount` nem foto |
| | `findContacts`, `findStatusMessage`, `archiveChat`, `deleteMessage`, `removeProfilePicture`, privacidade | ⬜ |

## Group
`groupJid` vai na query string e precisa ser um grupo (`...@g.us` ou o ID numérico); senão 400.
Grupo inexistente no stub dá 404; no Cobalt, o erro do WhatsApp vira 500.

| Método | Rota | Status |
|---|---|---|
| POST | `/group/create/{instance}` | ✅ 201 com o grupo; participantes repetidos contam uma vez |
| GET | `/group/findGroupInfos/{instance}` | ✅ `{id, subject, subjectOwner, subjectTime, pictureUrl, size, creation, owner, desc, restrict, announce, participants}` |
| GET | `/group/fetchAllGroups/{instance}` | 🟡 `getParticipants` opcional (padrão false). Cobalt: grupos do Store da sessão, consultados em paralelo; `pictureUrl` sempre null na lista |
| GET | `/group/participants/{instance}` | ✅ `{participants: [{id, admin}]}` |
| POST | `/group/updateParticipant/{instance}` | 🟡 `{updateParticipants: [{jid, status}]}` com status `"200"` ou `"400"` (sem o motivo) |
| POST | `/group/updateSetting/{instance}` | ✅ announcement / not_announcement / locked / unlocked → `{update: "success"}` |
| POST | `/group/updateGroupSubject`, `updateGroupDescription`, `updateGroupPicture` | ✅ `{update: "success"}`; foto só JPEG/PNG |
| GET | `/group/inviteCode/{instance}` | ✅ `{inviteUrl, inviteCode}` |
| POST | `/group/revokeInviteCode/{instance}` | ✅ `{revoked: true, inviteCode}` |
| GET | `/group/acceptInviteCode/{instance}?inviteCode=` | ✅ `{accepted: true, groupJid}`; aceita o código ou o link |
| DELETE | `/group/leaveGroup/{instance}` | ✅ `{groupJid, leave: true}` |
| | `inviteInfo`, `sendInvite`, `toggleEphemeral`, eventos `GROUPS_*` | ⬜ |

## Settings, Integrações (Typebot, Chatwoot etc.)
⬜ Fora do escopo: o foco é a API do WhatsApp.

## Erros
Todos no formato `{ "status", "error", "response": { "message": [...] } }`: validação, JSON
inválido, número inválido (400), instância ou rota inexistente (404), método não suportado (405)
e erro inesperado (500). O 401 mantém `message` como string, igual à Evolution. Número sem
WhatsApp (Cobalt): 400 com `message: [{"exists": false, "jid", "number"}]`, como na Evolution.

## Motor
O padrão é o stub, que não fala com o WhatsApp. `MSGFORGE_GATEWAY=cobalt` usa o Cobalt 0.0.10
(ADR 0007), validado ao vivo em 2026-11-12 (envio, recebimento, status e sessão; pendências no
ADR).

## Diferenças conhecidas
- Rotas de RabbitMQ: a Evolution usa filas por evento; aqui é um exchange tópico. Ajustar se algum consumidor depender dos nomes das filas.
- O payload de `MESSAGES_UPSERT` real depende do mapeamento Cobalt → formato Baileys (Fase 2).
- Número sem WhatsApp: com o Cobalt, o número é consultado antes do envio (400 `exists:false`) e
  a mensagem vai para o JID devolvido pelo WhatsApp (no Brasil, o número com o 9 extra resolve sem
  ele; o `remoteJid` da resposta já vem resolvido). O stub só valida o formato.
- O token da instância precisa ser único (constraint `uk_instance_token`).
