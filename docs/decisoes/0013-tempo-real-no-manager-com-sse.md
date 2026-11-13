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

## Implementação (2026-11)
- Rotas: `/manager/stream/instances` (evento `table`), `/manager/stream/instances/{nome}` (`live`) e
  `/manager/stream/instances/{nome}/messages` (`chats` e `conversation:{jid}`, só a página 1 da
  conversa aberta). Cada stream já abre com o trecho atual, o que cobre reconexões.
- `ManagerEventBridge` (depois do commit) → `ManagerBus` (`LocalManagerBus`) → `ManagerStreams`,
  que agrupa as mudanças de um canal em 250 ms, renderiza o fragmento uma vez (`FragmentRenderer`)
  e envia a todos. Até 4 streams por sessão (o navegador abre só 6 conexões por origem); o stream
  fecha a cada 15 min e na batida em que a sessão já não vale.
- **htmx-ext-sse 2.2.4** em `static/manager/vendor/` (integridade conferida com o sha512 do registry
  npm; licença 0BSD ao lado). `manager.js` suspende o polling (`data-poll`) enquanto o stream está
  aberto, com uma busca de segurança por minuto, e o retoma quando o stream cai.
- Pendente: o `ManagerBus` sobre Redis pub/sub, quando houver mais de um nó.

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
