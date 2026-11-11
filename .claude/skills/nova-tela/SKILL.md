---
name: nova-tela
description: Cria ou altera uma tela do Manager web do Msgforge (Thymeleaf em /manager). Use para qualquer trabalho de front-end, painel, template HTML, formulário ou página do projeto.
---

# Nova tela do Manager (Thymeleaf)

A decisão e o desenho estão no `docs/decisoes/0005-front-manager-thymeleaf.md`; leia-o antes,
porque os pontos marcados como *proposta* podem ter mudado.

1. **Controller** em `dev.msgforge.web` (`@Controller`, rota sob `/manager`):
   - Chame os **serviços** (`InstanceService`, `WebhookService`...); nunca repositórios e
     nunca a API REST por HTTP.
   - Use records próprios em `web/` para o modelo da tela. Não reaproveite os DTOs da API:
     eles são o contrato da Evolution.
   - Formulário: POST com token CSRF; em caso de sucesso, redirecione (padrão
     Post/Redirect/Get) e use flash attributes para a mensagem.
2. **Template** em `src/main/resources/templates/manager/<tela>.html`:
   - Use o layout e os fragmentos de `templates/manager/fragments/`.
   - Nada de texto fixo no HTML: use `#{chave}`, com as chaves em `messages.properties`.
   - Para atualização parcial (status, QR code), use htmx retornando um fragmento
     (`"manager/<tela> :: fragmento"`).
3. **Segurança:**
   - A rota tem de exigir sessão do manager.
   - Não exponha o token da instância em HTML, a menos que a tela exista para isso.
   - Escape sempre a saída (`th:text`; `th:utext` só com motivo explícito).
4. **Testes:** MockMvc para status, view e modelo, cobrindo o caso sem sessão (redireciona
   para o login). Se a tela mexer no fluxo de instância, estenda um `*IT`.
5. Depois de mudar código, rode `graphify update .`. Ao terminar uma tela da lista do
   ADR 0005, marque o progresso no `CLAUDE.md`.
