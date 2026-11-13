# 0012: Outbox no PostgreSQL para entregas de eventos e notificações

**Status:** proposta (2026-11-13). Substitui, quando implementada, a fila em memória da ADR 0006
para a entrega; mantém a ordem por instância (0006) e a entrega depois do commit, a partir de um
snapshot (0004).

## Contexto
As entregas de webhook e RabbitMQ ficam só em memória (ADR 0006). Se o processo cair durante os
retries, o evento se perde, e acima do teto por instância o evento novo é descartado (problema
conhecido 3 do `CLAUDE.md`). As notificações da ADR 0011 têm a mesma exigência. Confiabilidade de
entrega está no posicionamento do produto (`PRODUCT.md`).

## Decisão
- Tabela `outbox`: id sequencial, tipo (`WEBHOOK`, `WEBHOOK_GLOBAL`, `RABBITMQ`, `EMAIL`, ...),
  chave de ordem (a instância; para e-mail, o destinatário), destino, payload em `jsonb` (o
  snapshot do evento, como na 0004), estado (`PENDENTE`, `EM_ENVIO`, `FEITO`, `MORTO`),
  tentativas, próxima tentativa, último erro e datas.
- **Gravar antes de entregar:** o `EventDispatcher` grava a linha numa transação própria e só
  depois tenta a entrega. Se o processo cair, a linha está lá.
- **Ordem por chave:** um trabalhador pega o registro mais antigo `PENDENTE` de cada chave que
  não tenha outro `EM_ENVIO`, com `SELECT ... FOR UPDATE SKIP LOCKED`. Isso preserva a ordem da
  0006 e funciona com mais de um nó.
- **Acordar sem esperar:** `LISTEN/NOTIFY` do PostgreSQL acorda o trabalhador quando entra uma
  linha; uma varredura a cada segundo cobre o caso de a notificação se perder.
- **Retry** com espera exponencial, como hoje. Depois de N tentativas a linha vira `MORTO`: o
  Manager mostra as entregas com falha e permite reenviar.
- **Retenção:** linhas `FEITO` são apagadas em lotes depois de `OUTBOX_RETENTION_HOURS`, como o
  `HistoryRetention` faz.

## Alternativas consideradas
- **RabbitMQ como fila interna:** confiável, mas o RabbitMQ é opcional no Msgforge e o webhook
  passaria a depender dele.
- **Redis Streams:** rápido, mas a persistência do Redis é configurável e normalmente mais fraca
  que a do PostgreSQL, que já é a fonte da verdade.
- **CDC (Debezium):** padrão forte para outbox, mas é infraestrutura a mais (Kafka Connect).
- **Biblioteca de agendamento** (db-scheduler, JobRunr): resolve retry e varredura, mas não a ordem
  por chave do jeito da 0006, e adiciona um modelo de dados de terceiros. Uma tabela própria é
  pequena e fica sob nosso controle.

## Consequências
- Resolve o problema conhecido 3: queda do processo não perde entrega.
- Uma escrita por evento e destino. Instâncias de volume alto (centenas de milhares de mensagens)
  pedem índice pela chave de ordem e estado, gravação em lote e retenção curta; medir antes de
  ligar por padrão.
- Alguns milissegundos a mais por entrega, em troca de garantia.
- A tela de eventos (fase 3) ganha a saúde da entrega: pendentes, falhas e reenvio.
- A ADR 0006 passa a "substituída em parte por 0012" quando a outbox entrar em produção.
