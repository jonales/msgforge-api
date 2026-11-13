# Motores do WhatsApp

O motor é a parte que fala o protocolo do WhatsApp. Ele fica atrás de uma interface única
(`WhatsAppGateway`), então a API não muda quando o motor muda.

| `MSGFORGE_GATEWAY` | O que faz |
|---|---|
| `stub` (padrão) | Simula o WhatsApp: tudo funciona (instâncias, envio, eventos), mas nada sai de verdade. Bom para desenvolver e integrar sistemas. |
| `cobalt` | Conexão real pelo Cobalt 0.1.0, de [jonales/cobalt-2](https://github.com/jonales/cobalt-2) (instalado por `scripts/install-cobalt.sh`). A sessão fica em disco, uma pasta por instância (`COBALT_SESSION_DIR`). Valide ao vivo antes de produção (abaixo). |

## Cobalt: o motor deste repositório

O Cobalt roda dentro da própria aplicação (Java), sem serviço extra. O motor é mantido no fork
[jonales/cobalt-2](https://github.com/jonales/cobalt-2): ajustes de protocolo vão para lá, e
`scripts/install-cobalt.sh` fixa o commit usado na build. A decisão está no
[ADR 0007](decisoes/0007-motor-whatsapp-cobalt.md).

Uma variante desta mesma API com o motor whatsmeow (Go), num serviço separado, segue em
paralelo no repositório [jonales/msgforge-whatsmeow](https://github.com/jonales/msgforge-whatsmeow).

## Histórico das conversas no pareamento

Cada instância escolhe, no Manager (card "Conectar" ou ao criar), quanto histórico pede ao
WhatsApp quando o aparelho é vinculado: **sem histórico** (padrão, conexão mais rápida),
**recente** (o que o WhatsApp manda por padrão) ou **completo** (até 365 dias). A escolha só vale
no próximo pareamento: para mudar numa instância conectada, desconecte e conecte de novo.

## Antes de usar um número real

- Use um número de teste: a conexão não é oficial e pode levar ao banimento.
- Cobalt 0.1.0 (fork): rode o roteiro de validação ao vivo em
  [VALIDACAO-COBALT-FORK.md](VALIDACAO-COBALT-FORK.md).
