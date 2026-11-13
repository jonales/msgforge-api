# 0010: Organizações, usuários e papéis no Manager, e link de conexão para o cliente

**Status:** aceita (2026-11-13). Não muda a API pública: a `apikey` global e o token por
instância continuam como estão (`docs/COMPATIBILIDADE.md`).

## Contexto
O Manager tem um único jeito de entrar: a chave global, que libera tudo (ADR 0005). Isso serve
para uma pessoa só. O `PRODUCT.md` registra quatro perfis (integrador, operador, gestor,
atendente) e um quinto: o **cliente do gestor**, que só precisa conectar o próprio número. Hoje o
gestor não tem como dar a esse cliente um acesso mínimo; o token da instância também não serve,
porque abre tudo da instância. Com o e-mail (ADR 0011), dá para ter login com senha, convite e
redefinição de senha.

## Decisão
- **Organização** é o cliente do gestor. Cada instância pertence a uma organização
  (`instance.organization_id`). Na migração, as instâncias existentes vão para uma organização
  padrão.
- **Usuário** é identificado pelo e-mail (único, sem diferenciar maiúsculas), com senha guardada
  só como hash (`DelegatingPasswordEncoder` do Spring Security, Argon2 ou BCrypt) e estado
  `convidado`, `ativo` ou `bloqueado`.
- **Permissões fixas, papéis maleáveis.** O código define só um **catálogo de permissões** (uma
  `enum` de ações, verificada nos **serviços** por um `PermissionService`, e não só nos templates).
  **Papéis são dados:** cada organização monta os seus marcando permissões do catálogo, sem papel
  engessado. Catálogo inicial:

  | Grupo | Permissões |
  |---|---|
  | Instâncias | ver status · conectar, reconectar e ver QR · gerar link de conexão · criar · excluir |
  | Conversas | ver conversas · enviar mensagens |
  | Configuração | configurar eventos e webhook · configurar comportamento da conta |
  | Pessoas | convidar usuários · gerenciar papéis · ver auditoria |

- **Papéis-modelo**, prontos para usar, copiar e ajustar: **Gestor** (tudo na organização),
  **Operador** (status, conectar, link de conexão, conversas), **Atendente** (status e conversas) e
  **Cliente** (status e conectar). Uma organização pode criar quantos papéis quiser.
- **Quem define os papéis:** o **admin da plataforma** (acesso total, acima das organizações) e,
  dentro de cada organização, quem tiver "gerenciar papéis". Assim o gestor pode entregar ao
  próprio cliente a administração dos usuários da organização dele.
- **Escopo por instância (opcional):** o vínculo de uma pessoa pode valer para a organização
  inteira ou só para instâncias escolhidas (por exemplo, um atendente só do número de vendas).
- **Travas:** ninguém concede permissão que não tem (sem escalada); a organização mantém pelo
  menos uma pessoa com "gerenciar papéis"; papéis em uso não podem ser apagados sem reatribuir;
  toda criação e mudança de papel vai para a auditoria.
- **Login** por e-mail e senha no `/manager`, com o `LoginAttemptLimiter` que já existe.
  **Primeiro acesso:** sem nenhum usuário, o Manager pede a chave global uma vez para criar o
  primeiro administrador. Depois disso, a chave global deixa de entrar no Manager, a não ser que
  `MANAGER_KEY_LOGIN=true` (recuperação).
- **Convite e redefinição de senha** por e-mail: token aleatório de 32 bytes, guardado só como hash
  SHA-256, de uso único. O convite vale 72 h e a redefinição, 30 min. A resposta do "esqueci a
  senha" é sempre a mesma, exista ou não o e-mail.
- **Link de conexão** (acesso mínimo do cliente): quem tiver "gerar link de conexão" gera um link para **uma**
  instância. Ele abre uma página pública fora do `/manager` (`/conectar/{token}`, cadeia de
  segurança própria, mesmo CSP) que mostra só o QR ou o código de pareamento, o passo a passo e o
  status. O link expira (30 min por padrão), deixa de valer quando o número conecta, pode ser
  revogado e tem limite de tentativas por IP. O token também é guardado só como hash.
- **Auditoria mínima** desde o início: quem fez o quê, em qual organização, quando e de qual IP
  (login, convite, conectar, excluir, mudar eventos, gerar link).
- Sessão em memória enquanto houver um nó só; com mais de um, Spring Session sobre o Redis que já
  existe.

## Alternativas consideradas
- **Só o token da instância para o cliente:** reaproveita o que existe, mas é tudo ou nada dentro
  da instância (o cliente veria conversas e eventos). Descartado como acesso mínimo.
- **Provedor de identidade externo** (Keycloak, OIDC): robusto (2FA, SSO prontos), mas é mais um
  serviço para operar, contra o "um artefato só" do `PRODUCT.md`. A porta fica aberta: o Spring
  Security aceita OIDC depois sem mudar o modelo de papéis.
- **Usuários sem organizações:** mais simples, mas não representa o gestor com vários clientes.
- **Papéis fixos no código** (a primeira versão desta proposta): mais simples de testar, mas
  engessa o cliente, que precisa de combinações próprias. Ficou o catálogo fixo de permissões e os
  papéis como dados, com papéis-modelo para não começar do zero.

## Consequências
- Migrations novas (organização, usuário, vínculo, convite, redefinição, link, auditoria) e a
  coluna `organization_id` em `instance`, seguindo `docs/MIGRATIONS.md`.
- O `ManagerIT` e o login atual mudam; a API e os `*IT` da API não.
- Toda tela nova (fase 3) já nasce com o menu e as ações filtrados pelas permissões, e ganha uma
  tela de papéis (marcar permissões por grupo) e de pessoas (convidar, atribuir papel e escopo).
- Uma permissão nova no catálogo exige uma migration que a adicione aos papéis-modelo e uma
  decisão sobre os papéis já criados (por padrão, ninguém a recebe automaticamente).
- Ficam para ADRs próprias: 2FA (TOTP), chaves de API com escopo por organização e SSO.
