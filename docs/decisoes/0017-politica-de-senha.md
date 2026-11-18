# 0017: Política de senha do Manager

**Status:** aceita (2026-11-17)

## Contexto

O Manager tem login por e-mail e senha (ADR 0010) e ainda não tem MFA: a senha é o único fator. A regra
anterior era só tamanho (10 a 72 bytes). A pesquisa foi feita pela mesa de especialistas locais
(mesa `20261117-223303`), e a decisão conferiu cada ponto no texto das fontes:

- **NIST SP 800-63B rev. 4**: "SHALL require passwords that are used as a single-factor authentication
  mechanism to be a minimum of 15 characters"; "SHOULD permit a maximum password length of at least 64
  characters"; "SHALL NOT impose other composition rules"; "SHOULD accept Unicode"; espaços permitidos;
  "SHALL NOT require subscribers to change passwords periodically"; lista de bloqueio de valores
  "commonly used, expected, or compromised", e ela "só precisa ter entradas que cumpram o tamanho mínimo".
- **OWASP (Authentication e Password Storage)**: sem MFA, menos de 15 é fraca; o bcrypt usa só os
  primeiros 72 bytes.

## Decisão

`access/PasswordPolicy`, regra pura, aplicada na criação de usuário (`AccessAdminService`):

1. **Tamanho:** de 15 caracteres a 72 bytes. Os 72 bytes são o limite do bcrypt (recusar é melhor que
   truncar calado); cabem os 64 caracteres que o NIST pede, menos quando há muitos acentos.
2. **Sem regras de composição:** qualquer caractere, inclusive espaço e Unicode. A tela sugere frase longa.
3. **Bloqueio:** repetição (`aaaa...`, `senhasenhasenha`), sequência (teclado, números, alfabeto, nos dois
   sentidos), senha feita quase só do e-mail, do nome ou de "msgforge", e uma lista local curta de senhas
   comuns com 15+ caracteres (`security/senhas-comuns.txt`). Nada é consultado fora: um serviço de senhas
   vazadas receberia dados derivados da senha.
4. **Mensagem por motivo** (`manager.access.error.password.<motivo>`), dizendo o que fazer.
5. **Medidor ao digitar** no `manager.js`, com as mesmas regras que o navegador consegue ver; a lista de
   comuns só o servidor confere, e quem decide é o servidor.

## Consequências

- Senhas já gravadas continuam valendo: a política vale ao definir uma senha, e hoje só a criação de
  usuário define senha. A troca de senha e o "esqueci a senha" (fase 2) devem chamar a mesma política.
- O algoritmo continua bcrypt pelo `DelegatingPasswordEncoder`. A OWASP recomenda Argon2id; trocar exige
  dependência nova e migração gradual (o `DelegatingPasswordEncoder` permite) e fica para outra decisão.
- Normalização Unicode (NFKC) antes do hash, que o NIST recomenda, não foi feita: mudaria o hash das
  senhas existentes e precisa entrar junto no login.
