# Eventos

| Evento | Quando |
|---|---|
| `QRCODE_UPDATED` | Novo QR code ou código de pareamento |
| `CONNECTION_UPDATE` | Mudança de estado; ao conectar, traz o número (`wuid`) e o nome do perfil |
| `SEND_MESSAGE` | Mensagem enviada pela API |
| `MESSAGES_UPSERT` | Mensagem recebida (ou enviada por outro aparelho) |
| `MESSAGES_UPDATE` | Status: `SERVER_ACK`, `DELIVERY_ACK`, `READ`, `PLAYED` |

Cada evento chega num envelope com `event`, `instance`, `data`, `destination`, `date_time`,
`sender`, `server_url` e `apikey`. No RabbitMQ, a routing key é `{instancia}.{evento}` (ex.:
`vendas.messages.upsert`), num exchange tópico.

## Webhook e RabbitMQ

- **Webhook da instância:** `POST /webhook/set/{instancia}` (ver [api.md](api.md)). Com
  `byEvents`, cada evento vai para uma URL própria (`/messages-upsert`, `/connection-update`...).
- **Webhook global:** `WEBHOOK_GLOBAL_ENABLED` e `WEBHOOK_GLOBAL_URL` recebem os eventos de todas
  as instâncias.
- **RabbitMQ:** `RABBITMQ_ENABLED=true`.

As entregas acontecem depois que a operação é gravada no banco, **em ordem** por instância, com
timeout e retry exponencial (ADRs [0004](decisoes/0004-entrega-de-eventos-apos-commit.md) e
[0006](decisoes/0006-entrega-ordenada-por-instancia.md)).
