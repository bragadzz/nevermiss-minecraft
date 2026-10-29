#!/usr/bin/env bash
# Baixa (uma vez) o PocketMine-MP.phar oficial e o PHP do pmmp para macOS, com o SHA-256 conferido. Imprime o caminho do PHP.
set -euo pipefail
B="$(cd "$(dirname "$0")" && pwd)/baixados"
PM_VERSAO=5.44.3
PM_SHA=d8b4d87a64124d83659f629c87d6a8e0e142192f6c719fe4684272ff44105539
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) PHP_ARQ=PHP-8.2-MacOS-arm64-PM5.tar.gz PHP_SHA=eae469f7f7843b718e53021bbcc6a1a18f91b617cf7895a8cf8985451a66c8e6 ;;
  Darwin-x86_64) PHP_ARQ=PHP-8.2-MacOS-x86_64-PM5.tar.gz PHP_SHA=bca5c269f329a4f763719eb19772509373af1a6122a215bce4aa4c95e2743888 ;;
  *) echo "Sem PHP do pmmp configurado para $(uname -s)-$(uname -m): use PHP=/caminho/do/php" >&2; exit 1 ;;
esac

baixar() { # baixar <url> <arquivo> <sha256>
  if [ -f "$2" ] && echo "$3  $2" | shasum -a 256 -c - >/dev/null 2>&1; then return 0; fi
  curl -fsSL -o "$2.tmp" "$1"
  if ! echo "$3  $2.tmp" | shasum -a 256 -c - >/dev/null; then rm -f "$2.tmp"; echo "SHA-256 errado em $1" >&2; exit 1; fi
  mv "$2.tmp" "$2"
}

mkdir -p "$B"
baixar "https://github.com/pmmp/PocketMine-MP/releases/download/$PM_VERSAO/PocketMine-MP.phar" "$B/PocketMine-MP.phar" "$PM_SHA"
baixar "https://github.com/pmmp/PHP-Binaries/releases/download/pm5-php-8.2-latest/$PHP_ARQ" "$B/php.tar.gz" "$PHP_SHA"
[ -x "$B/bin/php7/bin/php" ] || tar xzf "$B/php.tar.gz" -C "$B"
echo "$B/bin/php7/bin/php"
