#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
read -r -p 'Email da primeira conta ADMIN: ' SOP_ADMIN_EMAIL
read -r -s -p 'Password (mínimo 12 caracteres, máximo 72 bytes UTF-8): ' SOP_ADMIN_PASSWORD
printf '\n'
read -r -s -p 'Repete a password: ' SOP_ADMIN_CONFIRMATION
printf '\n'
if [[ "$SOP_ADMIN_PASSWORD" != "$SOP_ADMIN_CONFIRMATION" ]]; then
  printf 'As passwords não coincidem. Não foi criada nenhuma conta.\n' >&2
  exit 1
fi
SOP_PASSWORD_BYTES=$(printf '%s' "$SOP_ADMIN_PASSWORD" | wc -c)
if (( ${#SOP_ADMIN_PASSWORD} < 12 || SOP_PASSWORD_BYTES > 72 )); then
  printf 'Conta não criada: a password exige pelo menos 12 caracteres e no máximo 72 bytes UTF-8. Executa novamente o script.\n' >&2
  exit 1
fi
export SOP_ADMIN_EMAIL SOP_ADMIN_PASSWORD
trap 'unset SOP_ADMIN_EMAIL SOP_ADMIN_PASSWORD SOP_ADMIN_CONFIRMATION' EXIT
if ! docker compose run --rm -T --no-deps \
  -e SPRING_PROFILES_ACTIVE=bootstrap-admin \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e SOP_ADMIN_EMAIL -e SOP_ADMIN_PASSWORD backend; then
  printf '\nConta não criada. Consulta a mensagem de erro acima antes de testar o login.\n' >&2
  exit 1
fi
printf '\nConta ADMIN criada com sucesso. Podes executar: python3 scripts/testar_login.py\n'
