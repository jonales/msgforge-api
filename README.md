# Msgforge API

API REST para WhatsApp em **Java 25 + Spring Boot 4.1**. Você cria instâncias (números), conecta
por QR code ou código de pareamento, envia e recebe mensagens, e recebe os eventos por webhook
ou RabbitMQ. Inclui um painel web (**Manager**) para operar tudo pelo navegador.

> Projeto independente, sem afiliação com o WhatsApp ou a Meta. A conexão é não oficial
> (protocolo do WhatsApp Web) e pode levar ao banimento do número: use com responsabilidade e
> faça testes com um número descartável.

## Recursos

- **Instâncias:** criar, conectar (QR code em texto e imagem, ou código de pareamento), consultar
  o estado, listar, desconectar e excluir. A sessão fica no PostgreSQL: a aplicação reinicia e a
  instância reconecta sozinha, sem QR.
- **Mensagens:** texto (com "digitando..." opcional), mídia (imagem, vídeo, áudio e documento) por
  URL ou base64, áudio de voz, localização, contatos (vCard), reações e enquetes.
- **Eventos:** QR code, conexão, mensagem enviada, mensagem recebida e status de entrega/leitura.
  Saem por webhook da instância, webhook global e RabbitMQ:
  - entregues depois que a operação é gravada no banco;
  - **em ordem** por instância;
  - com timeout e retry exponencial.
- **Segurança:**
  - Chave global ou token por instância (header `apikey`).
  - Bloqueio opcional de webhooks para a rede interna.
  - Manager com sessão, CSRF, CSP e limite de tentativas de login.
- **Manager:** painel em `/manager`, renderizado no servidor (Thymeleaf + htmx), sem build de
  front-end.

## Como rodar

```bash
cp .env.example .env          # troque AUTHENTICATION_API_KEY
docker compose up -d --build  # aplicação + PostgreSQL + Redis + RabbitMQ
curl http://localhost:8080/
```

Sem Docker, com JDK 25, PostgreSQL e Redis locais:

```bash
mvn spring-boot:run
```

- **API:** `http://localhost:8080`, com a documentação OpenAPI em `/docs`.
- **Manager:** `http://localhost:8080/manager`, com login pela `AUTHENTICATION_API_KEY`.

## Motores do WhatsApp

| `MSGFORGE_GATEWAY` | O que faz |
|---|---|
| `stub` (padrão) | Simula o WhatsApp: tudo funciona (instâncias, envio, eventos), mas nada sai de verdade. Bom para desenvolver e integrar sistemas. |
| `cobalt` | Conexão real pelo [Cobalt](https://github.com/Auties00/Cobalt). **Ainda não validado ao vivo:** siga o roteiro em [docs/spikes/cobalt](docs/spikes/cobalt/README.md) com um número de teste. |

## Exemplos

```bash
# Criar uma instância já pedindo o QR code
curl -X POST http://localhost:8080/instance/create \
  -H "apikey: troque-esta-chave" -H "Content-Type: application/json" \
  -d '{"instanceName":"vendas","qrcode":true}'

# Enviar texto
curl -X POST http://localhost:8080/message/sendText/vendas \
  -H "apikey: troque-esta-chave" -H "Content-Type: application/json" \
  -d '{"number":"5561999999999","text":"Olá do Msgforge"}'

# Receber os eventos num webhook (uma URL por evento: /messages-upsert, /connection-update...)
curl -X POST http://localhost:8080/webhook/set/vendas \
  -H "apikey: troque-esta-chave" -H "Content-Type: application/json" \
  -d '{"webhook":{"url":"https://meu-sistema.exemplo/hook","byEvents":true,"events":["MESSAGES_UPSERT","CONNECTION_UPDATE"]}}'
```

## Rotas

Todas exigem o header `apikey`: a chave global, ou o token da instância para as rotas dela.

| Método | Rota | Para quê |
|---|---|---|
| POST | `/instance/create` | Cria a instância (opcional: `qrcode`, `number`, `token`, `webhook`) |
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
| POST | `/webhook/set/{instancia}` | Configura o webhook da instância |
| GET | `/webhook/find/{instancia}` | Mostra o webhook configurado |

Os erros seguem um formato único: `{"status": 400, "error": "Bad Request", "response": {"message": [...]}}`.

### Eventos

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

## Configuração

As principais variáveis (todas em [.env.example](.env.example)):

| Variável | Padrão | Para quê |
|---|---|---|
| `AUTHENTICATION_API_KEY` | `troque-esta-chave` | Chave global da API e login do Manager |
| `SERVER_URL` | `http://localhost:8080` | URL pública, enviada nos eventos |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | PostgreSQL local | Banco (migrations Flyway automáticas) |
| `CACHE_REDIS_HOST`, `CACHE_REDIS_PORT` | `localhost:6379` | Cache do estado das instâncias |
| `MSGFORGE_GATEWAY` | `stub` | Motor do WhatsApp: `stub` ou `cobalt` |
| `WEBHOOK_GLOBAL_ENABLED`, `WEBHOOK_GLOBAL_URL` | desligado | Webhook que recebe os eventos de todas as instâncias |
| `WEBHOOK_REQUEST_TIMEOUT_MS`, `WEBHOOK_RETRY_*` | 10 s, 5 tentativas | Timeout e retry exponencial dos webhooks |
| `WEBHOOK_MAX_PENDING_PER_INSTANCE` | `1000` | Teto de entregas na fila de cada instância |
| `WEBHOOK_BLOCK_PRIVATE_NETWORKS` | `false` | Recusa webhooks de instância para IPs internos (SSRF) |
| `RABBITMQ_ENABLED` e `RABBITMQ_*` | desligado | Publica os eventos no RabbitMQ |
| `CONFIG_SESSION_PHONE_CLIENT` | `Msgforge` | Nome exibido em "Aparelhos conectados" (Cobalt) |
| `COBALT_PROXY` | vazio | Proxy para a conexão com o WhatsApp (Cobalt) |
| `FFMPEG_PATH` | `ffmpeg` | Binário do ffmpeg (já vem na imagem Docker); sem ele, a voz vai sem conversão |
| `MEDIA_TRANSCODE_TIMEOUT_MS` | `60000` | Limite de uma conversão de áudio |

Em produção atrás de HTTPS, ligue também `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` (cookie do
Manager).

## Arquitetura

```
dev.msgforge
├── instance/   rotas /instance, serviço de instâncias e estado (Redis + banco)
├── message/    rotas /message e MessageService
├── webhook/    rotas /webhook e configuração por instância
├── event/      eventos do motor → envelope → webhook / RabbitMQ (ordenado, com retry)
├── gateway/    WhatsAppGateway: única fronteira com o protocolo (stub e cobalt/)
├── security/   header apikey e proteção contra webhooks internos
├── web/        Manager (Thymeleaf + htmx, Spring Security só em /manager)
└── common/, config/
```

Recursos do Java 25 usados: virtual threads, records, `sealed interface` com pattern matching
exaustivo nos eventos e unnamed patterns. As decisões de arquitetura estão em
[docs/decisoes/](docs/decisoes/).

## Desenvolvimento

```bash
mvn test                              # testes unitários
mvn verify                            # + integração (PostgreSQL e Redis via Testcontainers)
node scripts/e2e/manager.js           # E2E do Manager (com a aplicação rodando no modo stub)
scripts/new-migration.sh "descricao"  # nova migration Flyway no padrão do projeto
```

- **Banco:** só por migration, no padrão de [docs/MIGRATIONS.md](docs/MIGRATIONS.md).
- **CI:** GitHub Actions (`mvn verify` + build da imagem Docker).
- **Memória para agentes (Claude Code):** [CLAUDE.md](CLAUDE.md), skills em `.claude/skills/` e
  [Graphify](https://github.com/Graphify-Labs/graphify) (`pip install "graphifyy[sql]==0.9.106"`,
  depois `graphify update .`). As memórias versionadas ficam em `graphify-out/memory/`.

## Roadmap

- [x] API de instâncias, mensagens e webhooks; persistência, RabbitMQ e motor stub
- [x] Eventos confiáveis: entrega após commit, em ordem, com retry e teto de memória
- [x] Motor real (Cobalt): QR/pareamento, sessão no banco, envio, recebimento e status
- [x] Manager v1: login, instâncias, QR ao vivo, webhook, mensagem de teste
- [ ] Validar o motor Cobalt ao vivo com um número de teste
- [x] Mais tipos de envio: áudio de voz, localização, contato, reação, enquete
- [ ] Figurinha (sticker), botões e listas
- [ ] Grupos, histórico de chat (buscar mensagens, marcar como lida), perfil
- [x] `ffmpeg` na imagem Docker (duração de áudio/vídeo, voz em OGG/Opus)
- [ ] Armazenamento de mídia (S3/MinIO)
- [ ] Testes de contrato automatizados da API
