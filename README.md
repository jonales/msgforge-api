# Msgforge API

API REST para WhatsApp em **Java 25 + Spring Boot 4.1**. Crie instâncias (números), conecte por
QR code ou código de pareamento, envie e receba mensagens e receba os eventos por webhook ou
RabbitMQ. Um painel web, o **Manager**, opera tudo pelo navegador.

> **Motor: Cobalt 0.1.0 (Java)**, do fork [jonales/Cobalt](https://github.com/jonales/Cobalt),
> rodando dentro da própria aplicação. Validado ao vivo em 2026-11-13 com dois números: texto,
> imagem, status, aparelhos vinculados e sessão restaurada; áudio ainda não testado
> ([roteiro e resultados](docs/VALIDACAO-COBALT-FORK.md)).

## O que faz

- **Instâncias:** criar, conectar (QR ou código), acompanhar o estado e reconectar sozinho no boot.
- **Mensagens:** texto, mídia, áudio de voz, localização, contatos, reações e enquetes.
- **Chat, perfil e grupos:** números no WhatsApp, leitura, presença, perfil, histórico e grupos
  completos (participantes, configurações e convites).
- **Eventos confiáveis:** webhook por instância, webhook global e RabbitMQ, em ordem e com retry.
- **Manager:** instâncias, QR ao vivo, webhook, mensagem de teste e conversas, direto no navegador.
- **Segurança:** chave global ou token por instância, limite de tentativas e proteção contra SSRF.

## Comece em 1 minuto

```bash
cp .env.example .env          # troque AUTHENTICATION_API_KEY
docker compose up -d --build  # aplicação + PostgreSQL + Redis + RabbitMQ
```

- **API:** `http://localhost:8080` (OpenAPI em `/docs`)
- **Manager:** `http://localhost:8080/manager`, com login pela `AUTHENTICATION_API_KEY`

```bash
curl -X POST http://localhost:8080/instance/create \
  -H "apikey: troque-esta-chave" -H "Content-Type: application/json" \
  -d '{"instanceName":"vendas","qrcode":true}'
```

Por padrão o motor é o `stub`, que simula o WhatsApp sem enviar nada de verdade. Para um número
real, veja [Motores do WhatsApp](docs/motores.md).

## Documentação

| | |
|---|---|
| [Instalação](docs/instalacao.md) | Docker, local, primeiros comandos e produção |
| [Configuração](docs/configuracao.md) | Variáveis de ambiente |
| [API](docs/api.md) | Rotas, autenticação e exemplos |
| [Eventos](docs/eventos.md) | Eventos, webhook e RabbitMQ |
| [Motores](docs/motores.md) | Stub e Cobalt |
| [Arquitetura](docs/arquitetura.md) e [decisões](docs/decisoes/README.md) | Como é feito e por quê |
| [Desenvolvimento](docs/desenvolvimento.md) | Testes, CI e padrões |
| [Roadmap](docs/roadmap.md) | O que está pronto e o que vem |

Índice completo em [docs/](docs/README.md).

---

Projeto independente, sem afiliação com o WhatsApp ou a Meta. A conexão é não oficial (protocolo
do WhatsApp Web) e pode levar ao banimento do número: use com responsabilidade e teste com um
número descartável.
