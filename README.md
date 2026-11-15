# Msgforge API

API REST para WhatsApp em **Java 25 + Spring Boot 4.1**. Crie instâncias (números), conecte por
QR code ou código de pareamento, envie e receba mensagens e receba os eventos por webhook ou
RabbitMQ. Um painel web, o **Manager**, opera tudo pelo navegador.

> **Motor: Cobalt 0.1.0 (Java)**, de [jonales/cobalt-2](https://github.com/jonales/cobalt-2),
> rodando dentro da própria aplicação. Validado ao vivo com dois números (o último teste foi em
> 2026-11-15): texto, imagem, vídeo, áudio, grupos (inclusive a foto), chamadas, eventos e sessão
> restaurada ([roteiro e resultados](docs/VALIDACAO-COBALT-FORK.md)).

## O que faz

- **Instâncias:** criar, conectar (QR ou código), acompanhar o estado e reconectar sozinho no boot.
- **Mensagens:** texto, imagem, vídeo, documento, áudio de voz, localização, contatos, reações e
  enquetes.
- **Chat, perfil e grupos:** números no WhatsApp, leitura, presença, perfil, histórico e grupos
  completos (participantes, configurações, foto e convites).
- **Eventos confiáveis:** webhook por instância, webhook global e RabbitMQ, em ordem e com retry,
  guardados numa fila no banco que sobrevive a quedas.
- **Manager:** painel web com as instâncias, a conexão ao vivo, as conversas (mídia e reações), as
  configurações e as integrações de cada número. Tema claro e escuro.
- **Atendimento:** várias pessoas por organização, com papéis e permissões; resposta pela conversa,
  assinatura do atendente e registro de quem enviou cada mensagem.
- **Bot com IA:** por instância, com qualquer modelo compatível com a API da OpenAI (inclusive um
  Ollama local). O cliente escolhe quando o bot entra e o que acontece quando um atendente assume.
- **Segurança:** chave global ou token por instância, login por e-mail, limite de tentativas,
  segredos cifrados no banco e proteção contra SSRF.

## Comece em 1 minuto

```bash
cp .env.example .env          # defina AUTHENTICATION_API_KEY (openssl rand -hex 32)
docker compose up -d --build  # aplicação + PostgreSQL + Redis + RabbitMQ
```

- **API:** `http://localhost:8080` (OpenAPI em `/docs`)
- **Manager:** `http://localhost:8080/manager`, com login pela `AUTHENTICATION_API_KEY` (depois,
  crie usuários com e-mail e senha em "Usuários")

```bash
curl -X POST http://localhost:8080/instance/create \
  -H "apikey: $AUTHENTICATION_API_KEY" -H "Content-Type: application/json" \
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

## Licença

[AGPL-3.0-only](LICENSE): use, estude, modifique e distribua à vontade. Quem oferecer a Msgforge
modificada como serviço pela rede precisa disponibilizar o código-fonte aos usuários. Para usar sem
essas obrigações (num serviço de código fechado, por exemplo), há licença comercial com o titular
([NOTICE](NOTICE)). Contribuições seguem o [CONTRIBUTING.md](CONTRIBUTING.md). As bibliotecas
distribuídas mantêm as próprias licenças ([THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)); o motor
cobalt-2 é MIT.

---

Projeto independente, sem afiliação com o WhatsApp ou a Meta. A conexão é não oficial (protocolo
do WhatsApp Web) e pode levar ao banimento do número: use com responsabilidade e teste com um
número descartável.
