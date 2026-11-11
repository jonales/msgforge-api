#!/bin/bash
# SessionStart das sessões do Claude Code em nuvem: JDK 25, dependências Maven,
# Graphify (memória e grafo do código). Idempotente; só reinstala o que mudou.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-.}"

GRAPHIFY_VERSION="0.9.106"
JDK_HOME="/usr/lib/jvm/java-25-openjdk-amd64"
GRAPHIFY_VENV="$HOME/.local/share/msgforge/graphify-venv"

# --- JDK 25 (a imagem padrão traz JDK 21) ----------------------------------------
if [ ! -x "$JDK_HOME/bin/javac" ]; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-25-jdk-headless >/dev/null
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# --- Dependências Maven (só quando o pom.xml ou este script mudar) ----------------
m2="$HOME/.m2/repository"
mkdir -p "$m2"
hashes="$m2/.session-start.sha256"
if ! sha256sum --check --status "$hashes" 2>/dev/null; then
  rm -f "$hashes"
  mvn -q -B dependency:go-offline
  mvn -q -B -DskipTests test-compile
  sha256sum .claude/hooks/session-start.sh pom.xml > "$hashes"
fi

# --- Graphify em venv próprio (só quando a versão/este script mudar) --------------
ghashes="$GRAPHIFY_VENV/.session-start.sha256"
if ! sha256sum --check --status "$ghashes" 2>/dev/null; then
  rm -rf "$GRAPHIFY_VENV"
  python3 -m venv "$GRAPHIFY_VENV"
  "$GRAPHIFY_VENV/bin/python" -m pip install -q --disable-pip-version-check "graphifyy[sql]==$GRAPHIFY_VERSION"
  sha256sum .claude/hooks/session-start.sh > "$ghashes"
fi
mkdir -p "$HOME/.local/bin"
ln -sf "$GRAPHIFY_VENV/bin/graphify" "$HOME/.local/bin/graphify"

# --- Variáveis para a sessão --------------------------------------------------------
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=\"$JDK_HOME\""
    echo "export PATH=\"$JDK_HOME/bin:\$HOME/.local/bin:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi

# --- Grafo do código (AST, sem LLM; usa cache). Falha aqui não impede a sessão -----
if ! "$GRAPHIFY_VENV/bin/graphify" update . >/dev/null 2>&1; then
  echo "aviso: graphify update falhou; rode 'graphify update .' manualmente" >&2
fi
