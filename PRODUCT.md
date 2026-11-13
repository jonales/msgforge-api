# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Quatro perfis usam o Manager (`/manager`), muitas vezes a mesma pessoa em momentos diferentes:

- **Desenvolvedor ou integrador:** liga a API a um sistema (CRM, ERP, bot). Cria instâncias,
  conecta números, configura webhook e fila, copia tokens e testa envios.
- **Operador ou suporte:** reconecta um número que caiu, confere o status e abre a conversa quando
  um cliente reclama que uma mensagem não chegou. Nem sempre é técnico.
- **Dono ou gestor:** acompanha quantas instâncias existem, quantas mensagens passam e se o
  serviço está saudável.
- **Atendente:** conversa com clientes pelo próprio Manager (ver "Capabilities and Constraints").

## Product Purpose

O Msgforge é uma API REST de WhatsApp que se apresenta só como Msgforge. O Manager é o painel web
para operar essa API sem escrever código: criar e conectar instâncias, configurar a entrega de
eventos, inspecionar conversas e mandar mensagens. Sucesso é o integrador colocar um número para
funcionar sem ler a documentação inteira, e o operador resolver um problema de conexão sem pedir
ajuda.

## Positioning

- **Compatível e confiável:** mantém o contrato público que clientes existentes já usam
  (`docs/COMPATIBILIDADE.md`) e entrega eventos com confiabilidade: depois do commit, em ordem por
  instância, com retry e teto de memória. O motor é validado ao vivo.
- **Tudo em Java:** um artefato só, na JVM, sem processo de outra linguagem ao lado. Cache, filas e
  integrações vêm do ecossistema Java (Spring, Redis, RabbitMQ).
- **Painel claro:** os fluxos não escondem decisões importantes. Exemplo: a escolha do histórico
  aparece no momento de conectar, e não numa tela de configurações separada. Textos em pt-BR.
- **Segurança e controle:** chave global e token por instância, limite de tentativas por IP,
  proteção contra SSRF nos webhooks, CSP restrito no Manager.

## Operating Context

- O Manager roda dentro da própria aplicação (Thymeleaf renderizado no servidor, htmx
  vendorizado), na rota `/manager`, protegido por login com a chave global.
- O usuário alterna entre o Manager, o celular (escanear QR, ver o aparelho conectado) e o sistema
  que integra a API (onde chegam os webhooks).
- Conectar um número é um momento de risco: um QR escaneado com a opção errada de histórico não se
  desfaz sem desconectar.

## Capabilities and Constraints

- **Canal atual:** só WhatsApp, pelo motor Cobalt (`jonales/cobalt-2`). Outros canais e integrações
  (bots, plataformas de atendimento, automação, outras filas) aparecem no painel como **"Em breve"**:
  visíveis no menu, sem função, até o backend existir. Nunca simular uma função que não existe.
- **Chat:** começa como inspeção e teste (ver o que entrou e saiu, mandar mensagem de teste) e deve
  evoluir para atendimento real. Decisões de tela não podem impedir essa evolução.
- **Eventos:** webhook por instância e global, RabbitMQ (exchange tópico) e histórico das mensagens
  no banco (ADR 0008).
- **Técnicas:** sem SPA e sem Node/npm; CSP só `'self'` (nada de script ou estilo inline, nada de
  CDN); textos em `messages.properties`; controllers MVC chamam serviços, nunca a API por HTTP.
- **Identidade:** o produto se apresenta só como Msgforge e não cita outros produtos como origem ou
  referência.
- **Em aberto:** configurações por instância (rejeitar ligações, ignorar grupos, sempre online,
  marcar como lidas), proxy por instância e entrega por WebSocket ou outras filas ainda não existem
  no backend.

## Brand Commitments

- Nome: Msgforge. Idioma da interface: português do Brasil.
- O Manager atual (`src/main/resources/static/manager/manager.css`) usa claro/escuro pelo sistema e
  verde como cor de ação; isso é implementação existente, não um compromisso de marca.

## Evidence on Hand

- Validação ao vivo do motor em `docs/VALIDACAO-COBALT-FORK.md`, com duas instâncias de teste.
- Não há depoimentos, clientes citáveis, métricas públicas ou preços. Não inventar.

## Product Principles

1. **Decisão no momento em que importa:** o que muda o resultado de uma ação (histórico ao conectar,
   eventos ao ligar um webhook) aparece junto da ação, com o padrão seguro já marcado.
2. **Estado sempre visível:** conexão, entrega de eventos e ações em andamento mostram o que está
   acontecendo e quando terminou.
3. **Honesto sobre o que existe:** o que ainda não funciona aparece como "Em breve", nunca como
   botão que falha.
4. **Serve do integrador ao atendente:** o caminho comum é curto para quem não é técnico; detalhes
   técnicos (tokens, eventos, JIDs) ficam a um clique, sem atrapalhar.

## Accessibility & Inclusion

- Navegação completa por teclado, foco visível e contraste mínimo de 4.5:1, nos temas claro e
  escuro.
- Diálogos com título acessível; nada depende só de cor para indicar estado.
