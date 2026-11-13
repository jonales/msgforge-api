# 0010: Organizações, usuários e papéis no Manager, e link de conexão para o cliente

**Status:** proposta (2026-11-13). Não muda a API pública: a `apikey` global e o token por
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
- **Papéis**, por vínculo entre usuário e organização:

  | Pode | Admin (plataforma) | Gestor | Operador | Atendente | Cliente |
  |---|---|---|---|---|---|
  | Todas as organizações | ✓ | – | – | – | – |
  | Criar e excluir instâncias | ✓ | ✓ | – | – | – |
  | Conectar, reconectar, ver QR | ✓ | ✓ | ✓ | – | ✓ |
  | Ver status | ✓ | ✓ | ✓ | ✓ | ✓ |
  | Ver conversas e enviar | ✓ | ✓ | ✓ | ✓ | – |
  | Configurar eventos, webhook, comportamento | ✓ | ✓ | – | – | – |
  | Convidar e gerenciar usuários | ✓ | ✓ | – | – | – |
  | Gerar link de conexão | ✓ | ✓ | ✓ | – | – |

  As permissões são verificadas nos **serviços** (um `PermissionService` com uma `enum` de ações),
  e não só nos templates; o template apenas esconde o que o papel não pode fazer.
- **Login** por e-mail e senha no `/manager`, com o `LoginAttemptLimiter` que já existe.
  **Primeiro acesso:** sem nenhum usuário, o Manager pede a chave global uma vez para criar o
  primeiro administrador. Depois disso, a chave global deixa de entrar no Manager, a não ser que
  `MANAGER_KEY_LOGIN=true` (recuperação).
- **Convite e redefinição de senha** por e-mail: token aleatório de 32 bytes, guardado só como hash
  SHA-256, de uso único. O convite vale 72 h e a redefinição, 30 min. A resposta do "esqueci a
  senha" é sempre a mesma, exista ou não o e-mail.
- **Link de conexão** (acesso mínimo do cliente): o gestor ou o operador gera um link para **uma**
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

## Consequências
- Migrations novas (organização, usuário, vínculo, convite, redefinição, link, auditoria) e a
  coluna `organization_id` em `instance`, seguindo `docs/MIGRATIONS.md`.
- O `ManagerIT` e o login atual mudam; a API e os `*IT` da API não.
- Toda tela nova (fase 3) já nasce com o menu e as ações filtrados por papel.
- Ficam para ADRs próprias: 2FA (TOTP), chaves de API com escopo por organização e SSO.
