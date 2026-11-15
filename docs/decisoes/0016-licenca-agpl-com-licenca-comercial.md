# 0016: Licença AGPL-3.0-only com licença comercial

**Status:** aceita (2026-11-15).

## Contexto
O projeto não tinha licença (todos os direitos reservados, por omissão). O titular não quer a MIT:
ela deixaria qualquer um fechar uma versão melhorada e vender como serviço. As dependências
permitem qualquer escolha: o motor (cobalt-2) é MIT; Spring e drivers, Apache-2.0 ou BSD.

## Decisão
- **AGPL-3.0-only** para o código da Msgforge (`LICENSE`), com **licença comercial** à parte para
  quem não quer as obrigações da AGPL (licenciamento duplo; `NOTICE`).
- **"only", e não "or-later":** o titular controla a licença; versões futuras da AGPL não passam a
  valer sozinhas.
- Cada arquivo Java começa com `// SPDX-License-Identifier: AGPL-3.0-only` e o copyright.
- Contribuições de terceiros concedem ao titular o direito de sublicenciar, inclusive sob a licença
  comercial (`CONTRIBUTING.md`, com `Signed-off-by`). Sem isso, a licença comercial não cobriria o
  código de outras pessoas.

Alternativas consideradas: BSL 1.1 (código visível, sem uso concorrente em produção, abre depois de
anos), mais restritiva e fora da definição de código aberto; proprietária; MIT ou Apache-2.0
(descartadas pelo titular).

## Consequências
- Quem roda a Msgforge modificada como serviço precisa oferecer o código aos usuários (seção 13).
  Integrar pela API REST, sem modificar a Msgforge, não obriga o sistema do cliente.
- Contribuições externas exigem a concordância com o `CONTRIBUTING.md`.
- O cobalt-2 continua MIT; os avisos das bibliotecas seguem em `THIRD-PARTY-NOTICES.md`.
