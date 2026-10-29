#!/usr/bin/env bash
# O laço do plugin contra a API falsa, sem servidor (como o NucleoTest.java). Termina com "TUDO OK".
# Uso: teste/unitario.sh   (PHP=/caminho/do/php para escolher o PHP; sem ele, usa o do pmmp)
set -euo pipefail
RAIZ=$(cd "$(dirname "$0")/.." && pwd)
PHP=${PHP:-$("$RAIZ/teste/baixar.sh")}
INI="$(dirname "$PHP")/php.ini"
CHAVE="nmmc_$(printf 'e%.0s' $(seq 43))"

nice node "$RAIZ/teste/api-falsa.mjs" 3971 "$CHAVE" > /dev/null &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
for _ in $(seq 50); do curl -fsS -X POST http://127.0.0.1:3971/teste/estado > /dev/null 2>&1 && break; sleep 0.1; done
if [ -f "$INI" ]; then nice "$PHP" -c "$INI" "$RAIZ/teste/nucleo-teste.php"; else nice "$PHP" "$RAIZ/teste/nucleo-teste.php"; fi
