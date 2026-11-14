# API

A API segue um contrato estável: rotas, campos e eventos não mudam entre versões. Diferenças e
limitações de cada rota estão em [COMPATIBILIDADE.md](COMPATIBILIDADE.md). A documentação
OpenAPI da instância em execução fica em `/docs`.

## Autenticação

O header `apikey` leva a chave global (acesso a tudo) ou o token da instância (acesso só às rotas
dela). Chave errada repetida a partir do mesmo IP leva a 429 por alguns minutos
(`AUTHENTICATION_MAX_FAILURES`, ver [configuracao.md](configuracao.md)); atrás de proxy, o IP é o
do cliente informado por um proxy confiável (`TRUSTED_PROXIES`).

O `token` escolhido no `/instance/create` precisa ter pelo menos 32 caracteres (senão, 400). Sem
`token`, a instância recebe um gerado (UUID, 36 caracteres), devolvido no campo `hash`. Tokens
criados antes dessa regra continuam valendo.

### Configurações e proxy no `/instance/create`

Todos opcionais; sem eles, vale o padrão (tudo desligado, sem proxy). São guardados na instância e
aplicados pelo motor Cobalt a cada conexão. O `fetchInstances` não os devolve.

| Campo | Tipo | Efeito |
|---|---|---|
| `syncFullHistory` | boolean | `true` pede até 365 dias de histórico no pareamento (o mesmo que "completo" no Manager); ausente ou `false`, nenhum |
| `rejectCall` | boolean | Recusa as chamadas recebidas |
| `msgCall` | string | Texto enviado a quem ligou (com ou sem `rejectCall`); sai como `send.message`. Até 4096 caracteres |
| `groupsIgnore` | boolean | Mensagens de grupo não viram `messages.upsert` (nem entram no histórico) |
| `readMessages` | boolean | Marca como lidas as mensagens recebidas, assim que chegam |
| `readStatus` | boolean | `true`: os status (`status@broadcast`) viram `messages.upsert` e são marcados como vistos. `false` (padrão): status são ignorados |
| `alwaysOnline` | boolean | Aceito e guardado, **ainda sem efeito** (ver COMPATIBILIDADE.md) |
| `proxyHost`, `proxyPort`, `proxyProtocol` | string | Proxy desta instância para a conexão com o WhatsApp, no lugar do `COBALT_PROXY`. Os três juntos; `proxyPort` de 1 a 65535 (texto ou número); `proxyProtocol`: `http`, `https`, `socks4`, `socks4a`, `socks5` (ou `socks`), `socks5h` |
| `proxyUsername`, `proxyPassword` | string | Credenciais do proxy (opcionais). A senha é guardada cifrada e exige `SECRETS_ENCRYPTION_KEY` no servidor (sem ela, 400). No `socks4`, só o usuário é usado |

Proxy incompleto, protocolo desconhecido, porta inválida ou senha sem usuário dão 400 e a instância
não é criada. O proxy não é testado no create: um proxy que não responde aparece no connect.

## Rotas

| Método | Rota | Para quê |
|---|---|---|
| POST | `/instance/create` | Cria a instância (opcional: `qrcode`, `number`, `token`, `webhook`, configurações e proxy, abaixo) |
| GET | `/instance/connect/{instancia}?number=` | Conecta: devolve o QR code ou, com `number`, o código de pareamento |
| GET | `/instance/connectionState/{instancia}` | Estado: `open`, `connecting` ou `close` |
| GET | `/instance/fetchInstances?instanceName=` | Lista as instâncias (com token de instância, só a própria) |
| DELETE | `/instance/logout/{instancia}` | Desconecta o WhatsApp e apaga a sessão |
| DELETE | `/instance/delete/{instancia}` | Exclui a instância |
| POST | `/message/sendText/{instancia}` | Envia texto (`number`, `text`, `delay`) |
| POST | `/message/sendMedia/{instancia}` | Envia mídia (`mediatype`, `media` em URL ou base64, `caption`, `fileName`) |
| POST | `/message/sendWhatsAppAudio/{instancia}` | Envia áudio de voz (`audio` em URL ou base64, qualquer formato; `encoding` (padrão true) converte para OGG/Opus com o ffmpeg) |
| POST | `/message/sendLocation/{instancia}` | Envia localização (`latitude`, `longitude`, `name`, `address`) |
| POST | `/message/sendContact/{instancia}` | Envia um ou mais contatos (`contact: [{fullName, phoneNumber, wuid, organization, email, url}]`) |
| POST | `/message/sendReaction/{instancia}` | Reage a uma mensagem (`key: {remoteJid, fromMe, id}`, `reaction`; vazio remove) |
| POST | `/message/sendPoll/{instancia}` | Envia enquete (`name`, `values` de 2 a 12, `selectableCount`: 1 = escolha única) |
| POST | `/chat/whatsappNumbers/{instancia}` | Quais números têm WhatsApp (`numbers`, até 100) → `[{exists, jid, number}]` |
| POST | `/chat/markMessageAsRead/{instancia}` | Marca como lidas (`readMessages: [{remoteJid, fromMe, id}]`) |
| POST | `/chat/sendPresence/{instancia}` | Presença na conversa (`presence`: available, unavailable, composing, recording, paused; `delay`) |
| POST | `/chat/fetchProfilePictureUrl/{instancia}` | URL da foto de perfil de `number` |
| POST | `/chat/fetchProfile/{instancia}` | Perfil de `number` (sem `number`, o da própria conta) |
| POST | `/chat/findMessages/{instancia}` | Histórico: `where.key` (`remoteJid`, `fromMe`, `id`), `page` e `offset` (tamanho da página, até 500) |
| POST | `/chat/findChats/{instancia}` | Conversas com a última mensagem, mais recentes primeiro |
| POST | `/chat/updateProfileName/{instancia}` | Altera o nome da conta (até 25 caracteres) |
| POST | `/chat/updateProfileStatus/{instancia}` | Altera o recado da conta (até 139 caracteres) |
| POST | `/chat/updateProfilePicture/{instancia}` | Altera a foto da conta (`picture`: JPEG/PNG em URL ou base64) |
| POST | `/group/create/{instancia}` | Cria grupo (`subject`, `description`, `participants`) |
| GET | `/group/findGroupInfos/{instancia}?groupJid=` | Dados do grupo, com participantes e foto |
| GET | `/group/fetchAllGroups/{instancia}?getParticipants=` | Grupos da conta |
| GET | `/group/participants/{instancia}?groupJid=` | Participantes (`admin`: null, admin ou superadmin) |
| POST | `/group/updateParticipant/{instancia}?groupJid=` | `action`: add, remove, promote ou demote; status por participante |
| POST | `/group/updateGroupSubject`, `updateGroupDescription`, `updateGroupPicture` `/{instancia}?groupJid=` | Assunto, descrição e foto |
| POST | `/group/updateSetting/{instancia}?groupJid=` | `action`: announcement, not_announcement, locked ou unlocked |
| GET | `/group/inviteCode/{instancia}?groupJid=` | Link e código do convite |
| POST | `/group/revokeInviteCode/{instancia}?groupJid=` | Novo código (o anterior deixa de valer) |
| GET | `/group/acceptInviteCode/{instancia}?inviteCode=` | Entra no grupo pelo código ou link |
| DELETE | `/group/leaveGroup/{instancia}?groupJid=` | Sai do grupo |
| POST | `/webhook/set/{instancia}` | Configura o webhook da instância |
| GET | `/webhook/find/{instancia}` | Mostra o webhook configurado |

Os erros seguem um formato único: `{"status": 400, "error": "Bad Request", "response": {"message": [...]}}`.

## Exemplos

```bash
# Criar uma instância já pedindo o QR code
curl -X POST http://localhost:8080/instance/create \
  -H "apikey: $AUTHENTICATION_API_KEY" -H "Content-Type: application/json" \
  -d '{"instanceName":"vendas","qrcode":true}'

# Enviar texto
curl -X POST http://localhost:8080/message/sendText/vendas \
  -H "apikey: $AUTHENTICATION_API_KEY" -H "Content-Type: application/json" \
  -d '{"number":"5561999999999","text":"Olá do Msgforge"}'

# Receber os eventos num webhook (uma URL por evento: /messages-upsert, /connection-update...)
curl -X POST http://localhost:8080/webhook/set/vendas \
  -H "apikey: $AUTHENTICATION_API_KEY" -H "Content-Type: application/json" \
  -d '{"webhook":{"url":"https://meu-sistema.exemplo/hook","byEvents":true,"events":["MESSAGES_UPSERT","CONNECTION_UPDATE"]}}'
```
