#!/bin/bash
# Repassa o hook do Claude Code para `graphify hook-guard <tipo>`.
# Sem graphify instalado, não faz nada (nunca bloqueia uma ferramenta).
bin=$(command -v graphify 2>/dev/null || true)
venv_bin="$HOME/.local/share/msgforge/graphify-venv/bin/graphify"
if [ -z "$bin" ] && [ -x "$venv_bin" ]; then bin="$venv_bin"; fi
[ -n "$bin" ] || exit 0
exec "$bin" hook-guard "$@"
