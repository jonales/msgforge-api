# 0009: Spike comparativo de motores; este repositório segue com o fork do Cobalt

**Status:** aceita (2026-11-12). Complementa a [0007](0007-motor-whatsapp-cobalt.md), que segue
valendo. A comparação abaixo levou a uma variante com whatsmeow (Go), que vive num repositório
separado (`jonales/msgforge-whatsmeow`). **Aqui o motor continua o Cobalt 0.1.0 do fork
`jonales/Cobalt`**, e os defeitos achados no spike viram trabalho de correção no fork.

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
- **Neste repositório:** o motor é o fork `jonales/Cobalt` (0.1.0), instalado por
  `scripts/install-cobalt.sh`. Os defeitos da coluna "Cobalt 0.1.0" acima são corrigidos no fork,
  com teste ao vivo (`docs/VALIDACAO-COBALT-FORK.md`), e entram aqui subindo o commit fixado.
  Prioridade: aviso de segurança no WhatsApp Web, decifrar mensagens de aparelhos vinculados e
  recibos de entrega/leitura.
- **Em paralelo:** um motor em Go sobre o whatsmeow (serviço separado, gRPC) segue no repositório
  `jonales/msgforge-whatsmeow`, com o mesmo contrato. O que for decidido lá não muda este
  repositório.

## Consequências
- Somos donos do fork: cada mudança de protocolo do WhatsApp pode exigir correção nele.
- O código de `gateway/cobalt/` continua sendo o caminho principal; contornos novos devem ir de
  preferência para o fork, não para reflexão aqui.
- Os dois repositórios compartilham o contrato (`docs/COMPATIBILIDADE.md`); uma mudança de contrato
  precisa chegar aos dois.
