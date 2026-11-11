---
type: "query"
date: "2026-11-11T14:04:28.665013+00:00"
question: "Como vai ser o front-end do Msgforge?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["InstanceService", "WebhookService", "ApiKeyFilter"]
---

# Q: Como vai ser o front-end do Msgforge?

## Answer

Decidido no ADR 0005 (ainda sem código): Manager em /manager, Thymeleaf renderizado no servidor + htmx vendorizado, sem SPA nem Node. Controllers MVC em dev.msgforge.web chamam os serviços (InstanceService, WebhookService), nunca repositórios nem a API por HTTP; modelos de tela são records próprios, separados dos DTOs da Evolution. Login com a chave global → sessão HTTP + CSRF; o ApiKeyFilter segue só para a API e deve ignorar /manager. Templates em templates/manager/, textos em messages.properties. Para criar telas, usar a skill nova-tela; o checklist das telas da v1 está no CLAUDE.md.

## Outcome

- Signal: useful

## Source Nodes

- InstanceService
- WebhookService
- ApiKeyFilter