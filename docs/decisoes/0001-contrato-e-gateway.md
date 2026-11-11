# 0001: Contrato público estável da API + motor atrás de `WhatsAppGateway`

**Status:** aceita (2026-11). Texto revisado na mesma data para tratar o contrato como do
próprio Msgforge (decisão do usuário); a decisão não mudou.

## Contexto
Os sistemas que vão usar o Msgforge já esperam um contrato HTTP conhecido: rotas, header
`apikey`, corpos, formato de erro e envelope de eventos. O motor do protocolo WhatsApp em Java
(Cobalt) ainda não estava validado no Java 25.

## Decisão
- O contrato público (rotas, `apikey`, corpos, erros, envelope de webhook) é fixado em
  `docs/COMPATIBILIDADE.md` e tratado como estável: nomes não mudam, mesmo quando parecem
  estranhos, porque clientes dependem deles.
- O motor fica isolado atrás da interface `WhatsAppGateway`; começa com o `StubWhatsAppGateway`.
- O gateway emite `GatewayEvent` (interface selada); só o `EventDispatcher` conhece o envelope
  do contrato.

## Consequências
- Dá para validar o contrato com os sistemas clientes antes de existir conexão real.
- Trocar ou atualizar o motor afeta só o pacote `gateway/`.
- Alguns nomes do contrato não seguem o estilo do resto do código, e é proposital.
