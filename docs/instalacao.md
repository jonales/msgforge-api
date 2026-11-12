# Instalação e primeiros passos

## Com Docker (recomendado)

```bash
cp .env.example .env          # troque AUTHENTICATION_API_KEY
docker compose up -d --build  # aplicação + PostgreSQL + Redis + RabbitMQ
curl http://localhost:8080/
```

## Sem Docker

Com JDK 25, PostgreSQL e Redis locais:

```bash
mvn spring-boot:run
```

## Onde acessar

- **API:** `http://localhost:8080`, com a documentação OpenAPI em `/docs`.
- **Manager:** `http://localhost:8080/manager`, com login pela `AUTHENTICATION_API_KEY`.

## Primeiros comandos

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

Todas as rotas estão em [api.md](api.md); os eventos, em [eventos.md](eventos.md).

## Produção

- Troque a `AUTHENTICATION_API_KEY` e veja as demais variáveis em [configuracao.md](configuracao.md).
- Atrás de HTTPS, ligue `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` (cookie do Manager).
- O motor `stub` (padrão) não fala com o WhatsApp: veja [motores.md](motores.md) antes de usar um
  número real.
