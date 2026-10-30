#!/usr/bin/env bash
# Apaga tudo o que o teste/vm-subir.sh e o teste/e2e.sh puseram no Mac: a VM (limactl delete), o Lima, os downloads (o BDS e
# o Lima), o cache de imagens do Lima e o ~/.lima (só se não tiver outra VM). Mostra o espaço liberado.
# Uso: teste/vm-apagar.sh
set -uo pipefail

VM=${NM_VM:-$HOME/frentes-lojas/mbd-vm}
NOME=nmbds
export LIMA_HOME="$VM/lima"
LIMACTL="$VM/lima-bin/bin/limactl"
CACHE="$HOME/Library/Caches/lima"

tamanho() { du -sk "$@" 2> /dev/null | awk '{s += $1} END {print s + 0}'; }
antes=$(tamanho "$VM" "$CACHE" "$HOME/.lima")
livre_antes=$(df -k "$HOME" | awk 'NR == 2 {print $4}')

if [ -x "$LIMACTL" ] && "$LIMACTL" list -q 2> /dev/null | grep -qx "$NOME"; then
  "$LIMACTL" stop -f "$NOME" > /dev/null 2>&1
  "$LIMACTL" delete -f "$NOME" || echo "AVISO: limactl delete falhou; a pasta da VM sai junto com $VM"
fi
rm -rf "$VM" "$CACHE"
# O teste usa o LIMA_HOME dentro de $VM; o ~/.lima só sai se não tiver nenhuma VM de outra pessoa dentro.
if [ -d "$HOME/.lima" ]; then
  if find "$HOME/.lima" -mindepth 1 -maxdepth 1 -type d ! -name '_*' | grep -q .; then
    echo "AVISO: ~/.lima tem outras VMs; não apaguei"
  else
    rm -rf "$HOME/.lima"
  fi
fi

livre_depois=$(df -k "$HOME" | awk 'NR == 2 {print $4}')
for p in "$VM" "$CACHE" "$HOME/.lima"; do [ -e "$p" ] && echo "SOBROU: $p"; done
echo "Apagado: $((antes / 1024)) MB (o disco ganhou $(((livre_depois - livre_antes) / 1024)) MB livres)"
