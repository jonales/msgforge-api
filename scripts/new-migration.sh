#!/usr/bin/env bash
# Cria uma migration Flyway no padrão do projeto (ver docs/MIGRATIONS.md).
# Uso: scripts/new-migration.sh "adiciona coluna profile_picture em instance"
#      scripts/new-migration.sh --repeatable "view instance stats"
set -euo pipefail

prefix="V$(date -u +%Y%m%d%H%M)"
if [[ "${1:-}" == "--repeatable" ]]; then
  prefix="R"
  shift
fi
if [[ $# -lt 1 || -z "$1" ]]; then
  echo "uso: $0 [--repeatable] \"descricao da mudanca\"" >&2
  exit 1
fi

# Remove acentos, deixa minúsculo e troca o que não for [a-z0-9] por _
desc=$(printf '%s' "$*" | LC_ALL=C.UTF-8 sed \
  'y/áàâãäéèêëíìîïóòôõöúùûüçñÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑ/aaaaaeeeeiiiiooooouuuucnAAAAAEEEEIIIIOOOOOUUUUCN/')
desc=$(printf '%s' "$desc" | LC_ALL=C tr '[:upper:]' '[:lower:]' | LC_ALL=C sed -E 's/[^a-z0-9]+/_/g; s/^_+|_+$//g')
[[ -n "$desc" ]] || { echo "descrição vazia depois da normalização" >&2; exit 1; }

dir="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/db/migration"
file="$dir/${prefix}__${desc}.sql"
if [[ -e "$file" ]]; then
  echo "já existe: $file" >&2
  exit 1
fi

cat > "$file" <<SQL
-- ${prefix}__${desc}
-- Motivo: $*
-- Lembretes: não edite depois do merge; atualize a entidade JPA no mesmo commit;
-- dê nome às constraints (pk_/fk_/uk_/ix_/ck_). Ver docs/MIGRATIONS.md.

SQL
echo "${file#"$(pwd)/"}"
