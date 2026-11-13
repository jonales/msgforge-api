# 0013: Tempo real no Manager com Server-Sent Events

**Status:** aceita (2026-11-13). Trata do próprio Manager. Entregar eventos ao vivo para os
sistemas dos clientes (WebSocket ou SSE na API) é outra decisão, que aparece como "Em breve".

## Contexto
O Manager se atualiza por *polling* do htmx: a lista de instâncias a cada 5 s, as conversas a cada
10 a 15 s, e o painel da instância também. Para o QR e o status, cada segundo conta; para o chat
virar atendimento (`PRODUCT.md`), mensagens precisam aparecer na hora. Com muitas abas abertas, o
polling gera carga sem novidade.

## Decisão
- **SSE** com `SseEmitter` do Spring MVC, em rotas sob `/manager/stream/...` (autenticadas pela
  sessão e filtradas pelas permissões da ADR 0010) e na página pública do link de conexão.
- O servidor envia **trechos de HTML** já renderizados pelo Thymeleaf; a extensão `sse` do htmx,
  vendorizada como o próprio htmx (sem CDN), troca o trecho na página. O CSP continua `'self'`
  (SSE é uma conexão para a mesma origem).
- **O que vai ao vivo:** status e QR da instância, mensagens novas da conversa aberta, contadores
  e a saúde das entregas (ADR 0012).
- **Origem dos eventos:** os `GatewayEvent` que já existem, por um barramento interno. Com um nó
  só, eventos do Spring; com mais de um, publicação no Redis (pub/sub), que já está na stack.
- **Robustez:** batida de coração a cada 25 s, reconexão automática do htmx, limite de conexões
  por usuário e `X-Accel-Buffering: no` para proxies. Se o SSE falhar, a tela volta ao polling.
- Threads virtuais (já ligadas) tornam barato manter as conexões abertas.

## Alternativas consideradas
- **WebSocket (STOMP):** bidirecional, útil para "digitando..." no atendimento; mais pesado de
  operar e o Manager só precisa receber. Fica como opção se o atendimento exigir.
- **Long polling:** funciona em qualquer lugar, mas reabre requisição a cada evento.
- **Manter o polling:** o mais simples, com o atraso e a carga de hoje.

## Consequências
- O QR e o status aparecem no mesmo instante em que o motor os produz.
- Quem opera atrás de um proxy (nginx) precisa desligar o buffering da rota; o
  `docs/configuracao.md` explica.
- Testes: um `*IT` que abre o stream e recebe o trecho após um evento do stub.
