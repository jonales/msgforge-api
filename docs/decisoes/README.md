# Registro de decisões (ADR)

Cada decisão de arquitetura relevante ganha um arquivo `NNNN-titulo-curto.md`, com:

- **Status:** proposta, aceita, substituída por NNNN
- **Contexto:** o problema e as restrições
- **Decisão:** o que escolhemos
- **Consequências:** o que ganhamos, o que perdemos e o que fica mais difícil

Uma decisão aceita não é editada; se ela mudar, crie um ADR novo que a substitui.

| Nº | Decisão | Status |
|---|---|---|
| [0001](0001-contrato-e-gateway.md) | Contrato público estável da API + motor atrás de `WhatsAppGateway` | aceita |
| [0002](0002-migrations-com-timestamp.md) | Migrations Flyway versionadas por timestamp | aceita |
| [0003](0003-memoria-do-projeto-graphify.md) | Memória do projeto: CLAUDE.md, ADRs e Graphify | aceita |
| [0004](0004-entrega-de-eventos-apos-commit.md) | Entrega de eventos depois do commit, a partir de um snapshot | aceita |
| [0005](0005-front-manager-thymeleaf.md) | Front-end (Manager) com Thymeleaf, renderizado no servidor | aceita |
| [0006](0006-entrega-ordenada-por-instancia.md) | Entrega de eventos ordenada por instância, com limite de pendências | aceita |
<<<<<<< Updated upstream
| [0007](0007-motor-whatsapp-cobalt.md) | Motor do WhatsApp: Cobalt (0.0.10, migrando para o fork 0.1.0), condicionado ao teste ao vivo | proposta |
| [0008](0008-historico-de-mensagens.md) | Histórico de mensagens no PostgreSQL, gravado pelos eventos do motor | aceita |
=======
<<<<<<< HEAD
| [0007](0007-motor-whatsapp-cobalt.md) | Motor do WhatsApp: Cobalt 0.0.10, condicionado ao teste ao vivo | substituída por 0008 |
| [0008](0008-motor-externo-whatsmeow.md) | Motor do WhatsApp: serviço externo em Go com whatsmeow | aceita |
=======
| [0007](0007-motor-whatsapp-cobalt.md) | Motor do WhatsApp: Cobalt (0.0.10, migrando para o fork 0.1.0), condicionado ao teste ao vivo | proposta |
| [0008](0008-historico-de-mensagens.md) | Histórico de mensagens no PostgreSQL, gravado pelos eventos do motor | aceita |
>>>>>>> 9a07653eabc4438cdf52ba426db64b8efda6d801
>>>>>>> Stashed changes
