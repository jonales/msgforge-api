# Configuração

As principais variáveis (todas em [.env.example](../.env.example)):

| Variável | Padrão | Para quê |
|---|---|---|
| `AUTHENTICATION_API_KEY` | `troque-esta-chave` | Chave global da API e login do Manager |
| `AUTHENTICATION_MAX_FAILURES` | `30` | Erros de chave da API por IP em 15 min antes do 429 (0 desliga) |
| `SERVER_URL` | `http://localhost:8080` | URL pública, enviada nos eventos |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | PostgreSQL local | Banco (migrations Flyway automáticas) |
| `CACHE_REDIS_HOST`, `CACHE_REDIS_PORT` | `localhost:6379` | Cache do estado das instâncias |
| `MSGFORGE_GATEWAY` | `stub` | Motor do WhatsApp: `stub` ou `cobalt` |
| `WEBHOOK_GLOBAL_ENABLED`, `WEBHOOK_GLOBAL_URL` | desligado | Webhook que recebe os eventos de todas as instâncias |
| `WEBHOOK_REQUEST_TIMEOUT_MS`, `WEBHOOK_RETRY_*` | 10 s, 5 tentativas | Timeout e retry exponencial dos webhooks |
| `WEBHOOK_MAX_PENDING_PER_INSTANCE` | `1000` | Teto de entregas na fila em memória de cada instância (só com `OUTBOX_ENABLED=false`) |
| `OUTBOX_ENABLED` | `true` | Grava cada entrega (webhook, global, RabbitMQ) na tabela `outbox` antes de entregar, e uma queda do processo não perde eventos (ADR 0012). `false` volta à fila em memória; as linhas pendentes ficam na tabela até religar |
| `OUTBOX_RETENTION_HOURS` | `24` | Apaga as entregas feitas mais velhas que isso, a cada 10 min (0 guarda para sempre). As que falharam de vez (`MORTO`) ficam |
| `OUTBOX_POLL_INTERVAL_MS` | `1000` | Varredura periódica da outbox, além do sinal de linha nova e do `LISTEN/NOTIFY` entre nós |
| `OUTBOX_MAX_IN_FLIGHT` | `64` | Entregas simultâneas por nó |
| `OUTBOX_LEASE_SECONDS` | `60` | Prazo de uma entrega em curso; vencido (o nó caiu), ela volta para a fila. Nunca fica abaixo de 3x `WEBHOOK_REQUEST_TIMEOUT_MS` |
| `WEBHOOK_BLOCK_PRIVATE_NETWORKS` | `false` | Recusa webhooks de instância para IPs internos (SSRF) |
| `RABBITMQ_ENABLED` e `RABBITMQ_*` | desligado | Publica os eventos no RabbitMQ |
| `CONFIG_SESSION_PHONE_CLIENT` | `Msgforge` | Nome do aparelho (Cobalt), aplicado só ao parear uma conexão nova; a sessão salva mantém o nome do perfil sincronizado |
| `COBALT_PROXY` | vazio | Proxy para a conexão com o WhatsApp (Cobalt) |
| `DATABASE_SAVE_DATA_NEW_MESSAGE` | `true` | Grava as mensagens no banco (histórico de `/chat/findMessages`) |
| `HISTORY_RETENTION_DAYS` | `0` | Apaga do histórico as mensagens mais velhas que isso, de hora em hora (0 guarda para sempre) |
| `COBALT_SESSION_DIR` | `~/.msgforge/cobalt` (`/data/cobalt` na imagem) | Pasta das sessões do Cobalt, uma subpasta por instância; monte um volume |
| `LOGGING_LEVEL_DEV_MSGFORGE_GATEWAY_COBALT_WIRE` | desligado | `DEBUG` registra os nós do protocolo do Cobalt (só atributos), para diagnóstico |
| `COBALT_LOG_LEVEL` | desligado | Liga os logs internos do Cobalt (`WARNING`, `INFO`, `DEBUG`...), para diagnóstico do motor |
| `FFMPEG_PATH` | `ffmpeg` | Binário do ffmpeg (já vem na imagem Docker); sem ele, a voz vai sem conversão |
| `MEDIA_TRANSCODE_TIMEOUT_MS` | `60000` | Limite de uma conversão de áudio |

Em produção atrás de HTTPS, ligue também `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` (cookie do
Manager).
