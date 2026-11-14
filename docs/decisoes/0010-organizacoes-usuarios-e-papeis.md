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

## Fase 2, fatias 1 e 2 (2026-11-14): decisões de implementação

Pontos que a decisão acima não fixava. Nada do que foi aceito muda.

- **Tabelas:** `organization`, `role` + `role_permission` (o papel é um nome e um conjunto de
  permissões), `app_user`, `membership` (usuário + organização + papel, único por par) e
  `membership_instance` (o escopo opcional; `membership.all_instances = true` vale para a
  organização inteira). `instance.organization_id` é `NOT NULL` com `DEFAULT` na organização
  padrão (`00000000-0000-0000-0000-000000000001`, "Padrão"): as instâncias que já existiam foram
  para ela, e as criadas pela API (que não conhece organizações) também vão. Organização com
  instâncias não é apagada (`ON DELETE RESTRICT`); papel em uso também não (`NO ACTION`, para o
  `CASCADE` de uma organização inteira continuar funcionando).
- **Catálogo:** a `enum Permission` tem 12 permissões nos 4 grupos da tabela acima. "Convidar
  usuários" virou `USERS_MANAGE` ("gerenciar usuários": criar, trocar o papel, bloquear), porque o
  convite por e-mail ainda não existe e a tela já faz o resto. "Conversas", nos papéis-modelo,
  é o grupo inteiro (ver e enviar) para Operador e Atendente. O `CHECK` de `role_permission` lista
  o catálogo; o `PermissionCatalogTest` lembra a migration e os textos de uma permissão nova.
  "Gerar link de conexão" e "ver auditoria" aparecem com "Em breve" na tela de papéis.
- **Pergunta única:** `AccessService.can(quem, permissão, instância)` respeita a organização da
  instância e o escopo do vínculo; `canInOrganization` responde as permissões da organização
  (usuários, papéis). As regras são puras (`AccessRules`) e têm teste próprio. As permissões não
  ficam na sessão: a sessão guarda só a identidade (`ManagerPrincipal`) e cada pergunta lê o
  banco, então um papel alterado vale na próxima requisição.
- **Admin da plataforma:** a chave global e o usuário com `app_user.platform_admin = true`. Só o
  admin cria outro admin e cria organizações (sem tela nesta fatia; a organização nova recebe os
  papéis-modelo de `RoleTemplate`).
- **Chave global no Manager:** `MANAGER_KEY_LOGIN` vem `true` nesta fase. Sem "esqueci a senha"
  por e-mail (ADR 0011, em outra branch), a chave é a única recuperação de quem perde a senha, e
  scripts e testes que entram com ela continuam funcionando. Sem nenhum usuário ela sempre entra
  (primeiro acesso); com `false`, só nesse caso. A intenção é virar `false` quando a redefinição
  por e-mail existir.
- **Primeiro usuário:** quem entra com a chave abre "Usuários" (no topo, ao lado de "Sair") e cria
  a pessoa com e-mail, papel e senha inicial (10 a 72 bytes, o limite do BCrypt), podendo marcá-la
  como admin da plataforma. Convite e redefinição por e-mail ficam para a fatia do e-mail.
- **Login:** um `POST /manager/login` só. Com o campo `apikey`, é a chave; senão, `username`
  (e-mail) e `password`. E-mail desconhecido compara a senha com um hash fixo, para o tempo não
  revelar quem tem conta. Senha certa de usuário bloqueado vai para `?disabled` (não conta no
  limitador); senha errada é sempre `?error`, contada no failure handler.
- **Usuário bloqueado perde a sessão** na próxima requisição (`ActiveSessionFilter`, só na
  cadeia do Manager), sem esperar a sessão expirar.
- **Travas:** sem escalada (só dá papéis, ou mexe em pessoas e papéis, cujas permissões a pessoa
  tem), "pelo menos uma pessoa ativa com gerenciar papéis" (quando a organização já tinha uma) e
  ninguém bloqueia a si mesmo. A auditoria ainda não existe; entra junto do convite.
- **Limite desta fase:** o papel controla só as telas de usuários e papéis. As telas de
  instâncias, conversas e os streams SSE ainda mostram tudo a quem está logado (fatia 5), e a tela
  de usuários avisa isso. Até lá, crie usuários só para quem já pode ver tudo.
