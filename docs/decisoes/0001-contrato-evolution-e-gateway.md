# 0001: Contrato da Evolution v2 + motor atrás de `WhatsAppGateway`

**Status:** aceita (2026-11)

## Contexto
Os sistemas que vão usar o Msgforge já falam com a Evolution API v2. O motor do protocolo
WhatsApp em Java (Cobalt) ainda não tem uma versão estável compatível com Java 25.

## Decisão
- Replicar rotas, header `apikey`, corpos, formato de erro e envelope de webhook da Evolution v2.
- Isolar o motor atrás da interface `WhatsAppGateway`; começar com `StubWhatsAppGateway`.
- O gateway emite `GatewayEvent` (interface selada); o `EventDispatcher` é o único que conhece
  o envelope da Evolution.

## Consequências
- Dá para validar o contrato com os sistemas atuais antes de existir conexão real.
- Trocar ou atualizar o motor afeta só o pacote `gateway/`.
- Herdamos as esquisitices de nome da Evolution, de propósito.
