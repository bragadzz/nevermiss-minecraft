#!/usr/bin/env bash
# Sobe a VM do teste e põe o BDS oficial dentro dela. O BDS só roda em Linux x86_64 e Windows; o Mac é arm64: a VM é um
# Ubuntu 24.04 arm64 no Lima (vmType vz) com o Rosetta rodando o bedrock_server x86_64.
# Tudo fica em ~/frentes-lojas/mbd-vm (o Lima, a VM e os downloads, com o SHA-256 conferido). teste/vm-apagar.sh apaga tudo.
# Baixar o BDS é aceitar a EULA do Minecraft (https://minecraft.net/eula) e a política de privacidade da Microsoft.
# Uso: teste/vm-subir.sh   (2 CPUs, 3 GB de RAM, 10 GB de disco)
set -euo pipefail

RAIZ=$(cd "$(dirname "$0")/.." && pwd)
VM=${NM_VM:-$HOME/frentes-lojas/mbd-vm}
NOME=nmbds
LIMA_VERSAO=2.2.1
LIMA_SHA=9e9eacce88f37e185c346bad73aa6136f738d8cdf8c3bb23cd42b071824bc66e
BDS_VERSAO=1.26.61.1
BDS_SHA=b54095267d55fd401de42f71ac4daf2c1747a49515ad41810781f1f9bafde26e
export LIMA_HOME="$VM/lima"
LIMACTL="$VM/lima-bin/bin/limactl"

baixar() { # baixar <url> <arquivo> <sha256>
  if [ -f "$2" ] && echo "$3  $2" | shasum -a 256 -c - >/dev/null 2>&1; then return 0; fi
  curl -fsSL -A "Mozilla/5.0" -o "$2.tmp" "$1"
  if ! echo "$3  $2.tmp" | shasum -a 256 -c - >/dev/null; then rm -f "$2.tmp"; echo "SHA-256 errado em $1" >&2; exit 1; fi
  mv "$2.tmp" "$2"
}

mkdir -p "$VM/baixados" "$VM/lima-bin" "$LIMA_HOME"
baixar "https://github.com/lima-vm/lima/releases/download/v$LIMA_VERSAO/lima-$LIMA_VERSAO-Darwin-arm64.tar.gz" "$VM/baixados/lima-$LIMA_VERSAO-Darwin-arm64.tar.gz" "$LIMA_SHA"
[ -x "$LIMACTL" ] || tar xzf "$VM/baixados/lima-$LIMA_VERSAO-Darwin-arm64.tar.gz" -C "$VM/lima-bin"
baixar "https://www.minecraft.net/bedrockdedicatedserver/bin-linux/bedrock-server-$BDS_VERSAO.zip" "$VM/baixados/bedrock-server-$BDS_VERSAO.zip" "$BDS_SHA"

if ! "$LIMACTL" list -q 2>/dev/null | grep -qx "$NOME"; then
  # Nenhuma porta da VM vai para o Mac. A VM só lê o addon e os downloads.
  cat > "$VM/$NOME.yaml" <<YAML
minimumLimaVersion: 2.0.0
base:
- template:_images/ubuntu-24.04
vmType: vz
vmOpts:
  vz:
    rosetta:
      enabled: true
      binfmt: true
cpus: 2
memory: 3GiB
disk: 10GiB
mounts:
- location: "$RAIZ"
  mountPoint: /mnt/addon
  writable: false
- location: "$VM/baixados"
  mountPoint: /mnt/baixados
  writable: false
portForwards:
- guestIP: "0.0.0.0"
  guestIPMustBeZero: false
  proto: any
  ignore: true
YAML
  nice "$LIMACTL" create --tty=false --name="$NOME" "$VM/$NOME.yaml"
fi
"$LIMACTL" list --format '{{.Status}}' "$NOME" 2>/dev/null | grep -qx Running || nice "$LIMACTL" start --tty=false "$NOME"

# O bedrock_server é x86_64 e só pede a libc e a libgcc (o resto vem dentro dele): o ld-linux e as duas em amd64 (o
# ports.ubuntu.com só tem arm64; o amd64 vem do archive.ubuntu.com). O Rosetta (binfmt) roda o binário.
"$LIMACTL" shell --workdir /tmp "$NOME" sudo bash -euo pipefail -s <<'SH'
if ! dpkg -s libc6:amd64 libgcc-s1:amd64 >/dev/null 2>&1; then
  dpkg --add-architecture amd64
  sed -i '/^Architectures:/d; s/^Types: deb$/Types: deb\nArchitectures: arm64/' /etc/apt/sources.list.d/ubuntu.sources
  cat > /etc/apt/sources.list.d/amd64.sources <<EOF
Types: deb
URIs: http://archive.ubuntu.com/ubuntu
Suites: noble noble-updates noble-security
Components: main universe
Architectures: amd64
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq unzip libc6:amd64 libgcc-s1:amd64 > /dev/null
fi
SH
"$LIMACTL" shell --workdir /tmp "$NOME" bash -euo pipefail -s <<SH
if [ ! -x ~/bds-original/bedrock_server ]; then
  rm -rf ~/bds-original && mkdir -p ~/bds-original
  unzip -q /mnt/baixados/bedrock-server-$BDS_VERSAO.zip -d ~/bds-original
  chmod +x ~/bds-original/bedrock_server
fi
echo "BDS $BDS_VERSAO em ~/bds-original na VM $NOME"
SH
