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
| POST | `/message/sendMedia/{instance}` | 🟡 (image/video/audio/document por URL ou base64; teto `COBALT_MEDIA_MAX_BYTES`) |
| POST | `/message/sendWhatsAppAudio/{instance}` | ⬜ |
| POST | `/message/sendLocation/{instance}` | ⬜ |
| POST | `/message/sendContact/{instance}` | ⬜ |
| POST | `/message/sendReaction/{instance}` | ⬜ |
| POST | `/message/sendButtons`, `sendList`, `sendPoll`, `sendSticker` | ⬜ |

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
| `/manager`: equivalente ao Evolution Manager, em Thymeleaf (ADR 0005) | ⬜ planejado |

## Chat, Group, Profile, Settings, Integrações (Typebot, Chatwoot etc.)
⬜ Fora do escopo inicial — o foco é só a API do WhatsApp.

## Erros
Todos no formato `{ "status", "error", "response": { "message": [...] } }`: validação, JSON
inválido, número inválido (400), instância ou rota inexistente (404), método não suportado (405)
e erro inesperado (500). O 401 mantém `message` como string, igual à Evolution.

## Motor
O padrão é o stub, que não fala com o WhatsApp. `MSGFORGE_GATEWAY=cobalt` usa o Cobalt 0.0.10
(ADR 0007), **ainda sem validação ao vivo**.

## Diferenças conhecidas
- Rotas de RabbitMQ: a Evolution usa filas por evento; aqui é um exchange tópico. Ajustar se algum consumidor depender dos nomes das filas.
- O payload de `MESSAGES_UPSERT` real depende do mapeamento Cobalt → formato Baileys (Fase 2).
- Número inválido: a Evolution consulta o WhatsApp (`onWhatsApp`) e responde 400 com `exists:false`;
  aqui a validação é só de formato (8 a 15 dígitos, grupo ou JID conhecido) até a Fase 2.
- O token da instância precisa ser único (constraint `uk_instance_token`).
