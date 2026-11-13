# Configuração

As principais variáveis (todas em [.env.example](../.env.example)):

| Variável | Padrão | Para quê |
|---|---|---|
| `AUTHENTICATION_API_KEY` | **obrigatória** | Chave global da API e login do Manager; 32+ caracteres (ex.: `openssl rand -hex 32`). A padrão (`troque-esta-chave`) só sobe no perfil `dev` |
| `SPRING_PROFILES_ACTIVE` | vazio | `dev` aceita a chave padrão ou fraca (com aviso no log); o `mvn spring-boot:run` já usa `dev`. Nunca em produção |
| `TRUSTED_PROXIES` | vazio | Proxies reversos confiáveis, em CIDR separados por vírgula (um IP só: `/32`): só deles o `X-Forwarded-For` é aceito. Vazio = cabeçalho ignorado |
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

## Manager atrás de um proxy (tempo real)

O Manager recebe as atualizações ao vivo (status, QR, conversas) por Server-Sent Events nas rotas
`/manager/stream/...` (ADR 0013). São respostas longas, que precisam chegar ao navegador evento a
evento. A aplicação já envia `X-Accel-Buffering: no`, que o nginx respeita. Em outro proxy, ou se
o cabeçalho for removido, desligue o buffering e a compressão dessa rota e aumente o tempo de
leitura (a aplicação manda uma batida a cada 25 s e fecha o stream a cada 15 min). No nginx:

```nginx
location /manager/stream/ {
    proxy_pass http://msgforge:8080;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    gzip off;
    proxy_read_timeout 1h;
}
```

Se o stream não abrir ou cair (rede, proxy que recusa, mais de 4 abas na mesma sessão), a tela
volta ao polling de antes. Com um proxy que abre o stream mas segura os eventos, a tela só se
atualiza na busca de segurança, a cada minuto: é o sinal de que falta desligar o buffering.

## Atrás de proxy reverso (nginx, Traefik)

Os limites de tentativas (chave da API e login do Manager) contam por IP. Atrás de um proxy, a
conexão chega sempre do IP do proxy: sem configuração, todos os clientes dividem um IP só, e quem
erra a chave de propósito bloqueia todo mundo (o 429 vale até com a chave certa).

Para contar pelo IP real, liste em `TRUSTED_PROXIES` os endereços de onde o proxy conecta na
aplicação, em CIDR (um IP só leva `/32`) e separados por vírgula:

```
TRUSTED_PROXIES=172.18.0.0/16            # rede Docker do proxy (docker network inspect <rede>)
TRUSTED_PROXIES=10.0.0.5/32,10.0.0.6/32  # proxies em IPs fixos
```

O `X-Forwarded-For` (e o `X-Forwarded-Proto`) só é aceito quando a conexão vem de um desses
endereços; de qualquer outro, é ignorado. Na cadeia `X-Forwarded-For`, o IP do cliente é o
primeiro, da direita para a esquerda, que não está na lista: um valor forjado pelo cliente à
esquerda não vale. Por isso o proxy precisa **acrescentar** o IP do cliente ao cabeçalho:

- **nginx:** `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` e
  `proxy_set_header X-Forwarded-Proto $scheme;`.
- **Traefik:** já envia o `X-Forwarded-For` acrescentado. Se houver outro balanceador na frente
  dele, configure `forwardedHeaders.trustedIPs` no entrypoint do Traefik com o IP desse
  balanceador; sem isso, o Traefik descarta o cabeçalho recebido (o que é seguro).

Não ponha em `TRUSTED_PROXIES` uma faixa de onde clientes também conectam direto (por exemplo, a
rede inteira quando a porta da aplicação está publicada): esses clientes poderiam forjar o IP. Com
a porta publicada pelo Docker, a conexão direta pode chegar pelo gateway da rede (`172.x.0.1`);
publique a porta só para o proxy ou deixe esse IP fora da lista.
