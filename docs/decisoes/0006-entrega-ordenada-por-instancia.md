# 0006: Entrega de eventos ordenada por instância, com limite de pendências

**Status:** aceita (2026-11). Complementa o 0004, que continua valendo: snapshot e entrega
depois do commit.
Substituída em parte pela 0012 (entrega pela outbox); o `OrderedExecutor` fica como recuo
(`OUTBOX_ENABLED=false`).

## Contexto
Desde o 0004, cada evento era entregue numa virtual thread própria. Com o retry de webhook,
um evento que falha pode ser entregue *depois* do evento seguinte. Por exemplo, um
`qrcode.updated` com retry chega depois do `connection.update: open`. Os consumidores da
API (n8n, CRMs, chatbots) tratam esses eventos como uma sequência, e com o motor real
da Fase 2 a inversão fica frequente. Além disso, sem limite, um destino fora do ar acumula
entregas pendentes na memória.

## Decisão
- `OrderedExecutor`: uma fila por instância, drenada por uma única virtual thread por vez.
  Eventos da mesma instância saem na ordem em que foram publicados, incluindo os retries.
  Instâncias diferentes continuam em paralelo.
- A fila é removida quando esvazia, então a memória acompanha só as instâncias com entregas
  pendentes.
- Teto por instância: `WEBHOOK_MAX_PENDING_PER_INSTANCE` (padrão 1000). Acima dele o evento
  novo é descartado, com log `WARN`.
- Uma tarefa que lança exceção não interrompe a fila.

## Consequências
- **Bloqueio na cabeça da fila:** um destino lento ou fora do ar atrasa os eventos seguintes
  da *mesma* instância, incluindo o webhook global e o RabbitMQ, que vão na mesma tarefa. O
  atraso máximo por evento é limitado pelo retry: com o padrão de 5 tentativas, 1+2+4+8 s de
  espera mais os timeouts. Ordem é mais importante que latência para esses consumidores.
- Se o destino ficar fora do ar por muito tempo, eventos são descartados depois do teto. A
  solução definitiva é um outbox persistente, que continua no backlog.
- Testes: `OrderedExecutorTest` (ordem sob concorrência, paralelismo entre instâncias, teto,
  falha isolada) e `InstanceFlowIT.eventsOfAnInstanceArriveInOrderEvenWhenOneIsRetried`. Este
  último falha se cada evento ganhar uma fila própria.
