# 0005: Front-end (Manager) com Thymeleaf, renderizado no servidor

**Status:** aceita (2026-11). A tecnologia está decidida; os detalhes marcados como *proposta*
podem mudar na implementação, e quem mudar atualiza este ADR.

## Contexto
A Evolution API tem um painel web, o *Evolution Manager*, em `/manager`, para criar instâncias,
ler o QR code, ver o status e configurar webhooks sem usar curl ou Postman. O Msgforge precisa
de algo equivalente, sobretudo a partir da Fase 2, quando o QR code passa a ser real.

Restrições: manter um só artefato (um JAR e uma imagem Docker), sem pipeline de Node/npm, e
reaproveitar a stack e os serviços que já existem.

## Decisão
- **Thymeleaf** (`spring-boot-starter-thymeleaf`), com HTML renderizado no servidor e servido
  pela própria aplicação. Sem SPA e sem build de front.
- **Rota base `/manager`**, igual à da Evolution.
- **Interatividade com htmx** *(proposta)*: atualização parcial de trechos da página (status,
  QR code que se renova) sem escrever JavaScript à mão. O arquivo fica vendorizado em
  `src/main/resources/static/manager/vendor/`, sem CDN.
- **Camadas:**
  - Controllers MVC ficam no pacote `dev.msgforge.web` (`@Controller`, não `@RestController`).
  - Eles chamam os **serviços** (`InstanceService`, `WebhookService`...); nunca repositórios e
    nunca a API REST por HTTP.
  - O formato JSON da Evolution continua sendo responsabilidade só da API.
  - Os DTOs de tela são records próprios em `web/`, para que mudar uma tela nunca altere o
    contrato da API.
- **Autenticação** *(proposta)*:
  - Tela de login que pede a chave global (`AUTHENTICATION_API_KEY`) e abre uma sessão HTTP.
  - O `ApiKeyFilter` continua valendo só para a API. `/manager/**` e os estáticos do manager
    entram nos prefixos que ele ignora e ganham uma proteção própria por sessão.
  - Formulários com token CSRF. Se a proteção crescer, avaliar o Spring Security só para
    `/manager/**`.
- **Organização dos arquivos:**
  - Templates em `src/main/resources/templates/manager/`, com layout e fragmentos em
    `templates/manager/fragments/`.
  - Textos em `src/main/resources/messages*.properties` (pt-BR como padrão).
  - CSS próprio e simples em `static/manager/`.
- **Telas da primeira versão:**
  1. Login.
  2. Lista de instâncias, com o status atualizado sozinho.
  3. Criar instância.
  4. Conectar, com QR code ou pairing code renovando a cada `qrcode.updated`.
  5. Configurar webhook (URL, `byEvents`, eventos e headers).
  6. Enviar mensagem de teste.
  7. Logout e exclusão de instância.
- **Testes:** controllers com MockMvc (status, view, modelo); fluxo completo nos `*IT`
  existentes.

## Implementação (v1, 2026-11)
As propostas acima viraram decisão assim:
- **htmx 2.0.11** vendorizado em `static/manager/vendor/` (integridade conferida com o sha512
  do registry npm; licença 0BSD ao lado). Só GETs de atualização: a lista a cada 5 s; o trecho
  "conexão" a cada 3 s enquanto não conecta e a cada 15 s depois. Todo POST é formulário normal.
- **Autenticação com Spring Security, restrito a `/manager/**`** (`ManagerSecurityConfig`):
  - Login por formulário com a chave global (comparação em tempo constante), sessão e CSRF.
  - CSP rígido: só `'self'`, e `data:` para a imagem do QR. Por isso o htmx roda com
    `includeIndicatorStyles: false`, porque ele injetaria um `<style>`.
  - Uma segunda cadeia libera a API sem sessão, sem CSRF e sem headers extras; o
    `ApiKeyFilter` ignora `/manager`. O `ManagerIT` garante que a API não mudou.
- **QR ao vivo:** `QrCodeCache` (camada web) escuta `QrCodeUpdated` e guarda o último código por
  instância, só em memória.
- **Erros:** `ManagerExceptionHandler` (só para o `ManagerController`) transforma erros dos
  serviços em mensagem na tela; o `ApiExceptionHandler` segue só para a API.
- **Envio:** novo `MessageService`, usado pela API e pelo Manager.
- **Token:** aparece **uma vez**, logo após criar; nenhuma tela o mostra depois.
- **Depois da revisão (v1.1):** limite de 10 falhas de login por IP em 15 min; cookie
  `SameSite=Strict`; com a sessão expirada, o polling do htmx recebe 401 + `HX-Redirect` em vez
  de encaixar a página de login no trecho; o QR do cache é descartado a cada novo "Conectar".
- **Telas entregues:** login; lista com estado; criar; detalhe com conectar (QR ou pairing),
  webhook, mensagem de teste, desconectar e excluir.

## Consequências
- Uma imagem só, sem toolchain de JavaScript; quem conhece Spring MVC mantém o front.
- O QR code ao vivo depende de *polling* via htmx ou de SSE. Cobre bem o caso (uma pessoa
  escaneando), mas não é um painel em tempo real para muitas instâncias.
- A segurança do manager (sessão + CSRF) é uma superfície nova, separada da apikey da API, e
  precisa de testes próprios.
- Quando começar: depois da Fase 2 (QR real), ou antes, sobre o gateway stub, se o painel
  ajudar a validar o fluxo.
