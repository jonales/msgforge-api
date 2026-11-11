# 0004: Entrega de eventos depois do commit, a partir de um snapshot

**Status:** aceita (2026-11)

## Contexto
O `EventDispatcher` entregava cada evento numa virtual thread que lia a instância e o webhook do
banco. Como o gateway publica eventos **dentro** da transação de quem o chamou
(`InstanceService.create`, `delete`...), a thread de entrega corria contra o commit:
- no `create` com `qrcode:true`, a instância ainda não estava visível e os eventos
  `qrcode.updated` e `connection.update` eram descartados;
- no `delete`, a instância podia já ter sido apagada e o `connection.update` (close) se perdia.

## Decisão
A entrega acontece em dois tempos:
1. **Síncrono, na transação de quem publicou:** atualiza o estado e copia token, ownerJid e a
   configuração do webhook para um `DeliveryTarget` imutável. Como roda na mesma transação,
   enxerga dados ainda não commitados.
2. **Assíncrono, depois do commit:** via `TransactionSynchronization.afterCommit`, ou na hora se
   não houver transação (por exemplo, eventos que chegam do motor do WhatsApp). A thread de
   entrega usa só o snapshot e nunca consulta o banco.

Rejeitamos o `@TransactionalEventListener(AFTER_COMMIT)` sozinho: no delete, depois do commit, a
instância já não existe, e qualquer escrita no `afterCommit` precisaria de `REQUIRES_NEW`.

O envio HTTP (`WebhookSender`) usa timeout, retry exponencial (erro de rede, 408, 429 e 5xx),
corpo em bytes (com `Content-Length`, sem `chunked`) e HTTP/1.1 fixo.

## Consequências
- Em rollback, nada é entregue, o que é a semântica correta.
- O webhook usado é o configurado no momento do evento, não o do momento da entrega.
- As entregas pendentes vivem em memória: se o processo cair durante os retries, elas se perdem.
  Se isso virar requisito, o próximo passo é um outbox transacional (tabela + worker).
- Os testes de regressão estão em `InstanceFlowIT` (`createWithQrCode...` e `deleteStill...`).
  Eles falham com a implementação antiga.
