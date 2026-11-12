# 0008: Histórico de mensagens no PostgreSQL, gravado pelos eventos do motor

**Status:** aceita (2026-11).

## Contexto
`/chat/findMessages` e `/chat/findChats` precisam das mensagens antigas. O Store do Cobalt
guarda conversas, mas em memória e dentro do blob da sessão: some num logout, não tem
paginação nem filtro e acopla a API ao motor (ADR 0001). O stub nem tem Store.

## Decisão
- Tabela `message`, uma linha por mensagem e por instância. A key do contrato
  `(remote_jid, key_id, from_me)` é única dentro da instância (`uk_message_key`). O conteúdo
  vai em `JSONB`, no mesmo formato do campo `message` do contrato.
- Quem grava é o `HistoryRecorder`, fora do gateway:
  - recebidas: escuta `MessageReceived`;
  - enviadas: gravadas com o conteúdo da resposta do `/message/*`;
  - status: escuta `MessageStatusUpdated`.
  Um motor novo ganha histórico sem código extra.
- Mensagem repetida (o WhatsApp reentrega) é ignorada com `ON CONFLICT DO NOTHING`.
- O status só avança (`ERROR < PENDING < SERVER_ACK < DELIVERY_ACK < READ < PLAYED`): um
  evento atrasado não desfaz um READ.
- SQL direto (`NamedParameterJdbcTemplate`), sem entidade JPA: o upsert, o `jsonb` e o
  `DISTINCT ON` das conversas ficam mais claros e baratos assim.
- Falha ao gravar vai para o log e não interrompe o envio nem a entrega dos eventos.
- `DATABASE_SAVE_DATA_NEW_MESSAGE=false` desliga a gravação.

## Consequências
- O histórico começa quando a instância passa a receber eventos: o que chegou antes (inclusive
  a sincronização inicial do celular) não aparece.
- A tabela cresce sem limite: ainda não há retenção nem limpeza. Excluir a instância apaga o
  histórico dela (`ON DELETE CASCADE`); o logout não.
- Mídia fica só com os metadados (sem download nem base64), como no `messages.upsert`.
- A gravação roda na thread que publicou o evento (no Cobalt, a do motor). Se o banco ficar
  lento, o recebimento fica lento junto. Isso fica aceitável enquanto o volume é de atendimento;
  com volume alto, a gravação vai para uma fila, do mesmo jeito do `OrderedExecutor`.
