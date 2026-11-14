# Eventos

| Evento | Quando |
|---|---|
| `APPLICATION_STARTUP` | A aplicação subiu. Vai só para o webhook global e o RabbitMQ (não pertence a nenhuma instância) |
| `QRCODE_UPDATED` | Novo QR code ou código de pareamento |
| `CONNECTION_UPDATE` | Mudança de estado; ao conectar, traz o número (`wuid`) e o nome do perfil |
| `SEND_MESSAGE` | Mensagem enviada pela API |
| `MESSAGES_SET` | Lote do histórico sincronizado no pareamento (só com `historySync` `RECENT` ou `FULL`) |
| `MESSAGES_UPSERT` | Mensagem recebida (ou enviada por outro aparelho) |
| `MESSAGES_UPDATE` | Status: `SERVER_ACK`, `DELIVERY_ACK`, `READ`, `PLAYED` |
| `MESSAGES_DELETE` | Mensagem apagada para todos |
| `CONTACTS_UPSERT` | Contatos sincronizados no login ou contato novo |
| `CONTACTS_UPDATE` | Nome do contato alterado na agenda (sincronizado entre os aparelhos) |
| `PRESENCE_UPDATE` | Presença de um contato (digitando, gravando, online, offline) nas conversas em que a conta assina a presença |
| `CHATS_UPSERT` | Conversas sincronizadas no login |
| `CHATS_UPDATE` | Conversa arquivada, silenciada, fixada, marcada como lida, trancada ou limpa em outro aparelho |
| `CHATS_DELETE` | Conversa apagada em outro aparelho |
| `GROUPS_UPSERT` | A conta criou um grupo ou foi adicionada a um |
| `GROUP_UPDATE` | Nome, descrição ou configuração (quem envia, quem edita) do grupo alterados |
| `GROUP_PARTICIPANTS_UPDATE` | Participantes adicionados, removidos, promovidos ou rebaixados |
| `CALL` | Chamada de voz ou vídeo recebida, e o fim dela |

Cada evento chega num envelope com `event`, `instance`, `data`, `destination`, `date_time`,
`sender`, `server_url` e `apikey`. No RabbitMQ, a routing key é `{instancia}.{evento}` (ex.:
`vendas.messages.upsert`), num exchange tópico; o `APPLICATION_STARTUP` sai com
`application.startup`, sem instância.

O nome em `webhook.events` é o da tabela. O nome que vai no campo `event` (e no sufixo da URL com
`byEvents`) é o mesmo em minúsculas, com ponto: `messages.upsert`, `/messages-upsert`. Duas
exceções, herdadas do contrato: `GROUP_UPDATE` sai como `groups.update` (`/groups-update`) e
`GROUP_PARTICIPANTS_UPDATE` como `group-participants.update` (`/group-participants-update`).

## Formato do `data`

JIDs no formato do contrato: `5561999999999@s.whatsapp.net`, `...@g.us`. Quando o WhatsApp
endereça por LID e o número não é conhecido, o JID sai como `<lid>@lid`, nunca como um número falso.

| Evento | `data` |
|---|---|
| `APPLICATION_STARTUP` | `{status: "started"}`; envelope com `instance`, `sender` e `apikey` null |
| `MESSAGES_SET` | lista de mensagens, cada uma no formato do `messages.upsert` |
| `MESSAGES_DELETE` | `{remoteJid, fromMe, id, participant?, status: "DELETED"}`: a conversa, quem apagou (`fromMe`, `participant` em grupo) e o `id` da mensagem apagada |
| `CONTACTS_UPSERT`, `CONTACTS_UPDATE` | lista de `{remoteJid, pushName, profilePicUrl, instanceId}`; sem nome, `pushName` é o número; `profilePicUrl` sempre null (use `/chat/fetchProfilePictureUrl`) |
| `PRESENCE_UPDATE` | `{id: <conversa>, presences: {<participante>: {lastKnownPresence, lastSeen?}}}`; `lastKnownPresence`: `available`, `unavailable`, `composing` ou `recording`; `lastSeen` em segundos |
| `CHATS_UPSERT` | lista de `{remoteJid, instanceId, name?, unreadMessages}` |
| `CHATS_UPDATE` | lista de `{remoteJid, instanceId}` |
| `CHATS_DELETE` | lista de JIDs |
| `GROUPS_UPSERT` | lista de `{id, subject, subjectOwner, subjectTime, size, creation, owner, desc, restrict, announce, participants: [{id, admin}]}`; `admin`: null, `admin` ou `superadmin` |
| `GROUP_UPDATE` | lista com um `{id, author?, subject?, desc?, announce?, restrict?}`: só o que mudou; descrição apagada vem como `desc: ""` |
| `GROUP_PARTICIPANTS_UPDATE` | `{id, author?, participants: [JID], action, participantsData: [{jid, phoneNumber?}]}`; `action`: `add`, `remove`, `promote`, `demote` ou `modify` |
| `CALL` | `{id, from, chatId, isGroup, groupJid?, isVideo, status, offline, date}`; `status`: `offer` (tocando), `accept` (atendida em outro aparelho), `reject`, `timeout` ou `terminate`; `date` em ISO-8601 |

## De onde vem cada evento

- **Cobalt** (`MSGFORGE_GATEWAY=cobalt`): todos. Contatos e conversas vêm da sincronização do
  login; o Cobalt repete a lista inteira a cada login, e só o que ainda não saiu nesta conexão
  sai de novo (depois de reiniciar o processo, a lista volta a sair inteira). `CHATS_UPDATE`,
  `CHATS_DELETE` e `CONTACTS_UPDATE` vêm das ações sincronizadas entre os aparelhos da conta. Os
  eventos de grupo vêm das notificações que o WhatsApp manda aos participantes. O `CALL` de fim
  completa conversa, grupo e vídeo com a oferta, quando o processo a viu tocar.
- **Stub** (padrão): `APPLICATION_STARTUP` e os de grupo, nas operações de `/group/*` (criar,
  alterar nome, descrição e configurações, participantes, sair). Os demais só o motor real gera.

## Webhook e RabbitMQ

- **Webhook da instância:** `POST /webhook/set/{instancia}` (ver [api.md](api.md)). Com
  `byEvents`, cada evento vai para uma URL própria (`/messages-upsert`, `/connection-update`...).
  Com `events`, só os eventos da lista são entregues.
- **Webhook global:** `WEBHOOK_GLOBAL_ENABLED` e `WEBHOOK_GLOBAL_URL` recebem os eventos de todas
  as instâncias; `WEBHOOK_GLOBAL_EVENTS` limita a lista.
- **RabbitMQ:** `RABBITMQ_ENABLED=true`; `RABBITMQ_EVENTS` limita a lista.

Evento que nenhum destino assina não é montado nem gravado na outbox. `PRESENCE_UPDATE` e
`CONTACTS_*` podem vir em volume: assine só se for usar.

As entregas acontecem depois que a operação é gravada no banco, **em ordem** por instância, com
timeout e retry exponencial (ADRs [0004](decisoes/0004-entrega-de-eventos-apos-commit.md) e
[0006](decisoes/0006-entrega-ordenada-por-instancia.md)).
