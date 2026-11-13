#!/bin/bash
# Instala o motor Cobalt (jonales/cobalt-2) no repositório Maven local.
# A versão 0.1.0 não está no Maven Central: a build do Msgforge depende desta instalação.
# Idempotente: não reinstala se o mesmo commit já foi instalado.
#
# Uso: scripts/install-cobalt.sh            (commit fixado abaixo)
#      COBALT_REF=<commit|branch> scripts/install-cobalt.sh
set -euo pipefail

COBALT_REPO="${COBALT_REPO:-https://github.com/jonales/cobalt-2.git}"
# Commit do fork testado com o Msgforge. Ao trocar, rode os testes e o roteiro ao vivo.
COBALT_REF="${COBALT_REF:-f5fb66ff263c116173d6986e1a579193e02d552b}"

m2="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
marker="$m2/com/github/auties00/cobalt-lib/0.1.0/.installed-ref"
if [ -f "$marker" ] && [ "$(cat "$marker")" = "$COBALT_REF" ]; then
  echo "cobalt $COBALT_REF já instalado"
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git init -q "$work/cobalt"
git -C "$work/cobalt" remote add origin "$COBALT_REPO"
git -C "$work/cobalt" fetch -q --depth 1 origin "$COBALT_REF"
git -C "$work/cobalt" checkout -q FETCH_HEAD
(cd "$work/cobalt" && mvn -q -B -DskipTests -Dmaven.javadoc.skip=true -Dmaven.repo.local="$m2" install -pl modules/lib -am)
mkdir -p "$(dirname "$marker")"
echo "$COBALT_REF" > "$marker"
echo "cobalt $COBALT_REF instalado"
