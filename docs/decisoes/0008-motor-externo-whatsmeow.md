# 0008: Motor do WhatsApp: serviço externo em Go com whatsmeow

**Status:** aceita (2026-11-12). Substitui a [0007](0007-motor-whatsapp-cobalt.md). O Cobalt 0.0.10
continua atrás de `MSGFORGE_GATEWAY=cobalt` até o motor novo ser validado; depois sai.

## Contexto
O teste ao vivo do Cobalt 0.0.10 (ADR 0007) achou, em uma noite, nove defeitos do motor que os
testes offline não alcançavam: estado de conexão errado, sessão ilegível por um bug de JID, fila
offline sem `offline_batch` nem ack, LID sem tradução, status que não disparam, versão do
WhatsApp Web obsoleta (login recusado com 405), cache que descartava envios em silêncio e envios
com falha que concluíam como sucesso. Contornamos todos em `gateway/cobalt/`, mas restou um que
não dá para contornar por fora: o app do destinatário mostrava "Não foi possível confirmar seu
código de segurança" nas mensagens do motor.

A causa comum é a mesma: o WhatsApp muda o protocolo o tempo todo (LID, versão, sessões entre
aparelhos), e o 0.0.10 é a última versão publicada, de julho de 2025. A reescrita do Cobalt
(0.1.0, `cobalt-lib`) existe só no Git, não foi publicada e está parada desde julho de 2026.

## Spike comparativo (2026-11-12)
Mesmos números de teste, mesmos critérios. As sondas rodaram em containers isolados.

| Critério | Cobalt 0.0.10 + contornos | Cobalt 0.1.0 (`main`) | whatsmeow |
|---|---|---|---|
| Aviso de segurança no celular | aparece | não aparece | não aparece |
| Aviso de segurança no WhatsApp Web | aparece | aparece | **não aparece** |
| Receber de um aparelho vinculado (Web) | pede reenvio | não decifra (sessão aberta pelo número, resposta pelo LID) | **decifra**, com número (`senderAlt`) e nome |
| Recibos de entrega/leitura como evento | só com o nosso rastreador | não dispara | nativo |
| Envio por LID | contorno nosso | sim | sim |
| Login após restart (sem QR) | ~60 s | ~3 s, com contorno de bug do builder | ~2 s |
| Versão do WhatsApp Web | contorno nosso | método quebrado (fixar à mão) | atualizada pela biblioteca |
| Manutenção | parada desde 2025 | não publicada, 4 meses parada | commits semanais |

O Cobalt 0.1.0 também tem bugs na própria API: `Options.Web.name()` faz cast para `Mobile`, e
`registered()` passa um handler nulo, então reabrir uma sessão salva falha.

## Decisão
O motor passa a ser um serviço próprio, `msgforge-engine`, em Go, sobre a biblioteca
[whatsmeow](https://github.com/tulir/whatsmeow) (MPL-2.0).

- **Fronteira:** a API Java continua dona do contrato (rotas, envelope, webhooks, ordem de
  entrega, Manager). Um `EngineWhatsAppGateway` implementa o `WhatsAppGateway` falando com o
  motor por gRPC: comandos (conectar, logout, envios) e um fluxo de eventos (QR, conexão,
  mensagem, recibo).
- **Mapeamento:** o motor entrega a mensagem recebida em JSON no formato do protobuf do WhatsApp;
  a conversão para o formato do contrato fica no Java, num lugar só.
- **Sessões:** no Postgres, num schema próprio do motor (o armazenamento da biblioteca), fora do
  Flyway da API. O `session_store` deixa de ser usado com o motor novo.
- **Versão da biblioteca:** o whatsmeow não publica versões numeradas. O motor fixa um commit e o
  atualiza de propósito, com os testes do motor e um teste ao vivo.
- **Licença:** a biblioteca é usada sem modificação. O aviso fica em `THIRD-PARTY-NOTICES.md`
  (exceção à regra de identidade, decidida pelo usuário). Se algum arquivo da biblioteca for
  modificado, a MPL obriga a publicar esse arquivo.

## Alternativas descartadas
- **Manter o Cobalt 0.0.10 com contornos:** cada mudança do WhatsApp vira um novo contorno por
  reflexão; o aviso de segurança não tem contorno.
- **Fork do Cobalt 0.1.0:** resolve parte do protocolo, mas falha justamente na sessão com
  aparelhos vinculados (a parte mais delicada), e nos tornaria donos de ~5 mil arquivos parados.
- **Motor próprio do zero em Java:** meses de trabalho até a paridade, manutenção contínua do
  protocolo e bibliotecas Signal em Java com licença AGPL/GPL.

## Consequências
- O deploy ganha um container (binário Go estático). O `docker compose` passa a ter o motor.
- Parte do código de `gateway/cobalt/` vira descarte quando o Cobalt sair (`LidResolver`,
  `SentStatusTracker`, `OfflineQueueGuard`, `UsersCache`, `WebVersion`). Ficam o contrato, os
  testes, o diagnóstico e o conhecimento do protocolo.
- O projeto passa a ter duas linguagens. O Go fica restrito ao motor; regras de negócio e
  contrato continuam no Java.
- As correções de protocolo chegam com as atualizações da biblioteca, não com trabalho nosso.
