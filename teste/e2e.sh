#!/usr/bin/env bash
# Teste de ponta a ponta: um servidor (ou proxy) de verdade com o plugin, falando com a API falsa.
# Uso: teste/e2e.sh <plataforma> <versão> [bedrock]
#   paper 1.20.4 | paper 1.7.10 | folia 1.21.11 | spigot 1.21.11 (o spigot.jar do BuildTools em teste/servidores/spigot-<versão>/servidor.jar)
#   velocity 3.5.1 | waterfall 1.21 | bungeecord latest: sobe também um Paper atrás do proxy (BACKEND=1.20.4),
#     com o plugin do servidor e outra chave, e confere que os dois entregam juntos.
#   bedrock: instala o Floodgate (e o Geyser, no servidor) e entrega para um jogador Bedrock.
#   fabric 1.20.1 | fabric 1.21.1 | fabric 26.3: o servidor pelo instalador oficial do Fabric (FABRIC_LOADER escolhe o
#     loader), só com o NeverMiss em mods/, sem o Fabric API. Sem bedrock (o Geyser do Fabric pede o Fabric API).
#   No servidor de jogo (menos o Folia), um jogador falso (teste/cliente.py) entra e recebe o Kit da NeverMiss.
# JAVA=/caminho/do/java escolhe o Java; JAVA_BACKEND, o do Paper atrás do proxy.
set -euo pipefail

PLATAFORMA=${1:?"Uso: teste/e2e.sh <plataforma> <versão> [bedrock]"}
VERSAO=${2:?"Uso: teste/e2e.sh <plataforma> <versão> [bedrock]"}
BEDROCK=${3:-}
RAIZ=$(cd "$(dirname "$0")/.." && pwd)
SERV="$RAIZ/teste/servidores"
DIR="$SERV/$PLATAFORMA-$VERSAO${BEDROCK:+-bedrock}"
FUNDO="$SERV/atras-$PLATAFORMA"
PORTA_API=${PORTA_API:-3409}
PORTA_MC=${PORTA_MC:-25599}
JAVA=${JAVA:-java}
JAVA_BACKEND=${JAVA_BACKEND:-$JAVA}
BACKEND=${BACKEND:-1.20.4}
API="http://127.0.0.1:$PORTA_API"
API2="http://127.0.0.1:$((PORTA_API + 1))"
CHAVE="nmmc_$(printf 'e%.0s' $(seq 43))"
CHAVE2="nmmc_$(printf 'f%.0s' $(seq 43))"
NOTCH=069a79f4-44e9-4726-a5be-fca90e38aaf5
VERSAO_PLUGIN=$(sed -n "s/^ *version = '\(.*\)'/\1/p" "$RAIZ/build.gradle")

case "$PLATAFORMA" in
  paper | spigot | folia) PROXY= ; JAR=bukkit ; PASTA=NeverMiss ; PRONTO='Done (' ; PARAR=stop ;;
  velocity) PROXY=1 ; JAR=velocity ; PASTA=nevermiss ; PRONTO='Done (' ; PARAR=shutdown ;;
  waterfall | bungeecord) PROXY=1 ; JAR=bungee ; PASTA=NeverMiss ; PRONTO='Listening on ' ; PARAR=end ;;
  fabric) PROXY= ; JAR=fabric ; PASTA=nevermiss ; PRONTO='Done (' ; PARAR=stop ;;
  *) echo "Plataforma desconhecida: $PLATAFORMA"; exit 2 ;;
esac
# A pasta do plugin (o executadas.txt e o resgates.txt). No Fabric é a config/nevermiss.
if [ "$PLATAFORMA" = fabric ]; then DADOS="$DIR/config/$PASTA"; else DADOS="$DIR/plugins/$PASTA"; fi
if [ "$PLATAFORMA" = fabric ] && [ -n "$BEDROCK" ]; then echo "O e2e do Fabric não tem Bedrock"; exit 2; fi
case "$PLATAFORMA" in paper | spigot) ESPERA=bukkit ;; waterfall | bungeecord) ESPERA=bungee ;; *) ESPERA=$PLATAFORMA ;; esac

falha() {
  echo "FALHOU: $*"
  echo "--- fim do log ---"; tail -40 "$DIR/saida.log" 2>/dev/null || true
  if [ -n "$PROXY" ]; then echo "--- fim do log do Paper atrás ---"; tail -15 "$FUNDO/saida.log" 2>/dev/null || true; fi
  exit 1
}

baixar() { # baixar <url> <sha256> <destino>
  curl -fsSL -o "$3.tmp" "$1"
  echo "$2  $3.tmp" | shasum -a 256 -c - >/dev/null
  mv "$3.tmp" "$3"
}
baixarPaperMC() { # baixarPaperMC <projeto> <versão> <destino>: da PaperMC, com o sha256 conferido
  [ -f "$3" ] && return 0
  read -r URL SHA < <(curl -fsS "https://fill.papermc.io/v3/projects/$1/versions/$2/builds/latest" |
    python3 -c 'import json,sys; d=json.load(sys.stdin)["downloads"]["server:default"]; print(d["url"], d["checksums"]["sha256"])')
  baixar "$URL" "$SHA" "$3"
}
baixarGeyserMC() { # baixarGeyserMC <floodgate|geyser> <spigot|velocity|bungee|bungeecord> <destino>: da GeyserMC
  [ -f "$3" ] && return 0
  read -r V B SHA < <(curl -fsSL "https://download.geysermc.org/v2/projects/$1/versions/latest/builds/latest" |
    python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["version"], d["build"], d["downloads"][sys.argv[1]]["sha256"])' "$2")
  baixar "https://download.geysermc.org/v2/projects/$1/versions/$V/builds/$B/downloads/$2" "$SHA" "$3"
}
antigo() { # o placar mudou de comando no 1.13
  python3 -c 'import sys; v=[int(x) for x in sys.argv[1].split(".")[:2]]; sys.exit(0 if v[0]==1 and v[1]<13 else 1)' "$1"
}
# servidorBukkit <pasta> <porta> <chave> <api>: pirata, só em 127.0.0.1, sem estatística (bStats), com o plugin
servidorBukkit() {
  rm -rf "$1/world" "$1/world_nether" "$1/world_the_end" "$1/logs" "$1/plugins/NeverMiss" "$1/plugins/"NeverMiss-*.jar
  mkdir -p "$1/plugins/NeverMiss" "$1/plugins/bStats"
  echo "eula=true" > "$1/eula.txt"
  # Sem compressão: o jogador falso não precisa do zlib. O 26.x vem com a whitelist ligada.
  printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=%s\nlevel-type=flat\ngenerate-structures=false\nspawn-protection=0\nenforce-secure-profile=false\nnetwork-compression-threshold=-1\nwhite-list=false\n' "$2" > "$1/server.properties"
  printf 'enabled: false\nserverUuid: 00000000-0000-0000-0000-000000000000\nlogFailedRequests: false\n' > "$1/plugins/bStats/config.yml"
  printf 'chave: "%s"\napi: "%s"\n' "$3" "$4" > "$1/plugins/NeverMiss/config.yml"
  cp "$RAIZ/bukkit/build/libs/NeverMiss-Bukkit-"*.jar "$1/plugins/"
}
# servidorFabric <pasta> <porta> <chave> <api>: o mesmo servidor, com o mod em mods/ e a config em config/nevermiss
servidorFabric() {
  rm -rf "$1/world" "$1/logs" "$1/config/nevermiss" "$1/mods/"NeverMiss-*.jar
  mkdir -p "$1/config/nevermiss" "$1/mods"
  echo "eula=true" > "$1/eula.txt"
  printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=%s\nlevel-type=flat\ngenerate-structures=false\nspawn-protection=0\nenforce-secure-profile=false\nnetwork-compression-threshold=-1\nwhite-list=false\n' "$2" > "$1/server.properties"
  printf 'chave: "%s"\napi: "%s"\n' "$3" "$4" > "$1/config/nevermiss/config.yml"
  cp "$RAIZ/fabric/build/libs/NeverMiss-Fabric-"*.jar "$1/mods/"
}
# instalarFabric <versão> <pasta>: o instalador oficial (maven.fabricmc.net, sha256 conferido) baixa o servidor da Mojang e o
# Fabric Loader; o servidor.jar é o lançador que ele gera.
instalarFabric() {
  [ -f "$2/servidor.jar" ] && return 0
  local INST LOADER URL
  INST=$(curl -fsS https://meta.fabricmc.net/v2/versions/installer | python3 -c 'import json,sys; print(next(x["version"] for x in json.load(sys.stdin) if x["stable"]))')
  LOADER=${FABRIC_LOADER:-$(curl -fsS https://meta.fabricmc.net/v2/versions/loader | python3 -c 'import json,sys; print(next(x["version"] for x in json.load(sys.stdin) if x["stable"]))')}
  URL="https://maven.fabricmc.net/net/fabricmc/fabric-installer/$INST/fabric-installer-$INST.jar"
  [ -f "$SERV/fabric-installer-$INST.jar" ] || baixar "$URL" "$(curl -fsS "$URL.sha256")" "$SERV/fabric-installer-$INST.jar"
  nice "$JAVA" -jar "$SERV/fabric-installer-$INST.jar" server -dir "$2" -mcversion "$1" -loader "$LOADER" -downloadMinecraft
  cp "$2/fabric-server-launch.jar" "$2/servidor.jar"
}

# 1) Os jars e a API falsa (o build também roda os testes).
(cd "$RAIZ" && ./gradlew -q build)
GSON=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name 'gson-2.10.1.jar' | head -1)
CP="$RAIZ/core/build/classes/java/test:$RAIZ/core/build/classes/java/main:$GSON"

# 2) O servidor (ou o proxy), da fonte oficial.
mkdir -p "$DIR/plugins"
case "$PLATAFORMA" in
  paper | folia | velocity | waterfall) baixarPaperMC "$PLATAFORMA" "$VERSAO" "$DIR/servidor.jar" ;;
  bungeecord)
    # O Jenkins do md_5 não publica sha256: fica o que foi baixado, e o sha256 sai aqui.
    [ -f "$DIR/servidor.jar" ] || curl -fsSL -o "$DIR/servidor.jar" https://ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar
    shasum -a 256 "$DIR/servidor.jar" ;;
  spigot) [ -f "$DIR/servidor.jar" ] || falha "falta o $DIR/servidor.jar (java -jar BuildTools.jar --rev $VERSAO)" ;;
  fabric) instalarFabric "$VERSAO" "$DIR" ;;
esac
# O Waterfall baixa os comandos dele (glist, alert...) da api.papermc.io/v2, que foi desligada: vão antes, da v3.
if [ "$PLATAFORMA" = waterfall ] && [ ! -d "$DIR/modules" ]; then
  mkdir -p "$DIR/modules"
  curl -fsS "https://fill.papermc.io/v3/projects/waterfall/versions/$VERSAO/builds/latest" |
    python3 -c 'import json,sys; [print(k[7:], v["url"], v["checksums"]["sha256"]) for k, v in json.load(sys.stdin)["downloads"].items() if k.startswith("module:")]' |
    while read -r NOME URL SHA; do baixar "$URL" "$SHA" "$DIR/modules/$NOME.jar"; done
fi
# O Paperclip do 1.7.10 corta o download do jar da Mojang: vai antes, com o sha1 do manifesto.
if [ "$PLATAFORMA-$VERSAO" = paper-1.7.10 ] && [ ! -f "$DIR/cache/mojang_1.7.10.jar" ]; then
  mkdir -p "$DIR/cache"
  curl -fsSL -o "$DIR/cache/mojang_1.7.10.jar" https://launcher.mojang.com/v1/objects/952438ac4e01b4d115c5fc38f891710c4941df29/server.jar
  echo "952438ac4e01b4d115c5fc38f891710c4941df29  $DIR/cache/mojang_1.7.10.jar" | shasum -a 1 -c - >/dev/null
fi

# 3) A configuração. O proxy é original (online-mode), para o {uuid} vir da Mojang.
rm -f "$DIR/plugins/"floodgate*.jar "$DIR/plugins/"geyser*.jar
if [ "$PLATAFORMA" = fabric ]; then
  servidorFabric "$DIR" "$PORTA_MC" "$CHAVE" "$API"
elif [ -z "$PROXY" ]; then
  servidorBukkit "$DIR" "$PORTA_MC" "$CHAVE" "$API"
  if [ -n "$BEDROCK" ]; then
    baixarGeyserMC floodgate spigot "$SERV/floodgate-spigot.jar"
    baixarGeyserMC geyser spigot "$SERV/geyser-spigot.jar"
    cp "$SERV/floodgate-spigot.jar" "$SERV/geyser-spigot.jar" "$DIR/plugins/"
    # O Geyser só em 127.0.0.1, numa porta UDP que não é a padrão, e sem estatística.
    mkdir -p "$DIR/plugins/Geyser-Spigot"
    [ -f "$DIR/plugins/Geyser-Spigot/config.yml" ] ||
      printf 'bedrock:\n  address: 127.0.0.1\n  port: 19199\n  clone-remote-port: false\nmetrics:\n  enabled: false\n' > "$DIR/plugins/Geyser-Spigot/config.yml"
  fi
else
  rm -rf "$DIR/logs" "$DIR/plugins/$PASTA" "$DIR/plugins/"NeverMiss-*.jar
  mkdir -p "$DIR/plugins/$PASTA" "$DIR/plugins/bStats"
  printf 'chave: "%s"\napi: "%s"\n' "$CHAVE" "$API" > "$DIR/plugins/$PASTA/config.yml"
  cp "$RAIZ/$JAR/build/libs/NeverMiss-"*.jar "$DIR/plugins/"
  printf 'enabled=false\nserver-uuid=00000000-0000-0000-0000-000000000000\nlog-errors=false\nlog-sent-data=false\nlog-response-status-text=false\n' > "$DIR/plugins/bStats/config.txt"
  if [ "$PLATAFORMA" = velocity ]; then
    printf 'config-version = "2.7"\nbind = "127.0.0.1:%s"\nmotd = "NeverMiss"\nonline-mode = true\nplayer-info-forwarding-mode = "legacy"\n[servers]\nlobby = "127.0.0.1:%s"\ntry = ["lobby"]\n[forced-hosts]\n[advanced]\n[query]\nenabled = false\n' \
      "$PORTA_MC" "$((PORTA_MC - 1))" > "$DIR/velocity.toml"
  else
    printf 'online_mode: true\nip_forward: true\nlisteners:\n- host: 127.0.0.1:%s\n  query_enabled: false\n  priorities:\n  - lobby\nservers:\n  lobby:\n    address: 127.0.0.1:%s\n    motd: NeverMiss\n    restricted: false\n' \
      "$PORTA_MC" "$((PORTA_MC - 1))" > "$DIR/config.yml"
  fi
  if [ -n "$BEDROCK" ]; then
    if [ "$JAR" = velocity ]; then GM=velocity; else GM=bungee; fi
    baixarGeyserMC floodgate "$GM" "$SERV/floodgate-$GM.jar"
    cp "$SERV/floodgate-$GM.jar" "$DIR/plugins/"
  fi
  # O Paper atrás do proxy (modo BungeeCord), com o plugin do servidor e outra chave.
  mkdir -p "$SERV/paper-$BACKEND" "$FUNDO/cache"
  baixarPaperMC paper "$BACKEND" "$SERV/paper-$BACKEND/servidor.jar"
  cp "$SERV/paper-$BACKEND/servidor.jar" "$FUNDO/servidor.jar"
  if [ -d "$SERV/paper-$BACKEND/cache" ]; then cp -R "$SERV/paper-$BACKEND/cache/." "$FUNDO/cache/"; fi
  servidorBukkit "$FUNDO" "$((PORTA_MC - 1))" "$CHAVE2" "$API2"
  printf 'settings:\n  bungeecord: true\n' > "$FUNDO/spigot.yml"
fi
# Uma entrega que "estava rodando" quando o servidor caiu (T15).
echo "mcinterrompida rodando $(($(date +%s) * 1000))" > "$DADOS/executadas.txt"

# 4) Sobe a API falsa (lease 5 s, próxima consulta 10 s) e o servidor. O console de cada um é um fifo.
PIDS=""
trap 'kill $PIDS 2>/dev/null || true' EXIT
nice "$JAVA" -cp "$CP" com.nevermissapps.minecraft.ApiFalsa "$PORTA_API" "$CHAVE" 5000 10 > "$DIR/api.log" 2>&1 &
PIDS="$PIDS $!"
noLog() { grep -q -- "$1" "$DIR/saida.log"; }
noLogDe() { grep -q -- "$2" "$1/saida.log"; }
ligar() { # ligar <pasta> <java> <fd>
  rm -f "$1/console.fifo" && mkfifo "$1/console.fifo"
  (cd "$1" && exec nice "$2" -Xmx1G -jar servidor.jar nogui < console.fifo > saida.log 2>&1) &
  LIGOU=$!
  PIDS="$PIDS $LIGOU"
  eval "exec $3> \"$1/console.fifo\""
}
esperarLigar() { # esperarLigar <pasta> <texto> <pid>
  for _ in $(seq 300); do noLogDe "$1" "$2" && return 0; kill -0 "$3" 2>/dev/null || falha "$1 não subiu"; sleep 1; done
  falha "$1 não terminou de ligar"
}
if [ -n "$PROXY" ]; then
  nice "$JAVA" -cp "$CP" com.nevermissapps.minecraft.ApiFalsa "$((PORTA_API + 1))" "$CHAVE2" 5000 10 > "$FUNDO/api.log" 2>&1 &
  PIDS="$PIDS $!"
  ligar "$FUNDO" "$JAVA_BACKEND" 4
  PID_FUNDO=$LIGOU
  esperarLigar "$FUNDO" 'Done (' "$PID_FUNDO"
  echo "Paper $BACKEND no ar atrás do proxy."
fi
ligar "$DIR" "$JAVA" 3
PID_MC=$LIGOU
console() { echo "$*" >&3; }

entrega() { curl -fsS -X POST "${2:-$API}/teste/entregas" -d "$1" >/dev/null; }
estado() {
  curl -fsS "${2:-$API}/teste/estado" | python3 -c 'import json,sys; e=json.load(sys.stdin)["entregas"].get(sys.argv[1]) or {}; print(e.get("estado"), e.get("motivo"), e.get("tentativas"))' "$1"
}
espera() { # espera <id> <"estado motivo"> <segundos> [api]
  for _ in $(seq "$3"); do
    case "$(estado "$1" "${4:-}")" in "$2"*) return 0 ;; esac
    sleep 1
  done
  falha "$1 ficou em '$(estado "$1" "${4:-}")', esperava '$2'"
}

sleep 2
curl -fsS "$API/teste/estado" >/dev/null || falha "a API falsa não subiu"
esperarLigar "$DIR" "$PRONTO" "$PID_MC"
echo "$PLATAFORMA $VERSAO${BEDROCK:+ com Floodgate} no ar."

# 5) As entregas. No proxy, os comandos são do console do proxy (o glist existe no Velocity e no BungeeCord).
if [ -n "$PROXY" ]; then
  OK="glist all"; INTERROMPIDA="glist Interrompido"; BLOQ="glist bloqueada"; UUID_CMD="glist {uuid}"
  BEDROCK_CMD="glist {nick}"; BEDROCK_RODOU="glist .Cool_Guy"
elif [ "$PLATAFORMA" = folia ]; then
  # O Folia não tem o /scoreboard: conta o que o say escreveu no console.
  OK="say Steve comprou vip"; INTERROMPIDA="say Interrompido comprou vip"; BLOQ="say isto nao pode rodar"
  UUID_CMD="say {uuid}"; BEDROCK_CMD="say {nick} comprou vip"; BEDROCK_RODOU="say .Cool_Guy comprou vip"
else
  OK="scoreboard players add Steve vip 1"; INTERROMPIDA="scoreboard players add Interrompido vip 1"; BLOQ="say isto nao pode rodar"
  UUID_CMD="scoreboard players add {uuid} vip 1"; BEDROCK_CMD="scoreboard players add {nick} vip 1"
  BEDROCK_RODOU="scoreboard players add .Cool_Guy vip 1"
  console "scoreboard objectives add vip dummy"
  sleep 1
fi
entrega "{\"id\":\"mcinterrompida\",\"nick\":\"Interrompido\",\"comandos\":[\"$INTERROMPIDA\"]}"
entrega "{\"id\":\"mc1\",\"nick\":\"Steve\",\"comandos\":[\"$OK\"]}"
entrega "{\"id\":\"mcbloqueada\",\"nick\":\"Steve\",\"comandos\":[\"$BLOQ\",\"op Steve\"]}"
entrega "{\"id\":\"mcdesligar\",\"nick\":\"Steve\",\"comandos\":[\"$BLOQ\",\"end\"]}"
entrega "{\"id\":\"mcrecarregar\",\"nick\":\"Steve\",\"comandos\":[\"$BLOQ\",\"velocity reload\"]}"
entrega "{\"id\":\"mcsufixo\",\"nick\":\"Cool Guy#1234\",\"edicaoJogador\":\"bedrock\",\"comandos\":[\"$BEDROCK_CMD\"]}"
entrega "{\"id\":\"mcuuid\",\"nick\":\"Notch\",\"comandos\":[\"$UUID_CMD\"]}"
entrega '{"id":"mcteste","evento":"teste","comandos":[]}'
entrega '{"id":"mcfalha","nick":"Steve","comandos":["comandoquenaoexiste Steve"]}'
entrega "{\"id\":\"mcbedrock\",\"nick\":\"Cool Guy\",\"edicaoJogador\":\"bedrock\",\"comandos\":[\"$BEDROCK_CMD\"]}"
if [ -n "$PROXY" ]; then entrega '{"id":"mcfundo","nick":"Steve","comandos":["say coexiste"]}' "$API2"; fi

espera mcinterrompida "falhou interrompida" 40
espera mc1 "entregue" 40
espera mcbloqueada "falhou bloqueado" 5
espera mcdesligar "falhou bloqueado" 5
espera mcrecarregar "falhou bloqueado" 5
espera mcsufixo "falhou bloqueado" 5
if [ -n "$PROXY" ]; then
  espera mcuuid "entregue" 15
  entrega "{\"id\":\"mcsemconta\",\"nick\":\"Ninguem_zz_9871\",\"comandos\":[\"$UUID_CMD\"]}"
  espera mcsemconta "falhou nick_nao_existe" 30
else
  espera mcuuid "falhou uuid_pirata" 5
fi
espera mcteste "entregue" 5
espera mcfalha "falhou comando_falhou" 5
if [ -n "$BEDROCK" ]; then espera mcbedrock "entregue" 5; else espera mcbedrock "falhou sem_bedrock" 5; fi
if [ -n "$PROXY" ]; then espera mcfundo "entregue" 40 "$API2"; fi
echo "T13/T15/T17/T19/T21 e Bedrock: ok"

# 6) Confirmação perdida 2 vezes (T14): volta pelo lease, o plugin não roda de novo.
curl -fsS -X POST "$API/teste/cortar" >/dev/null
curl -fsS -X POST "$API/teste/cortar" >/dev/null
entrega "{\"id\":\"mc2\",\"nick\":\"Steve\",\"comandos\":[\"$OK\"]}"
espera mc2 "entregue None 2" 60
echo "T14: ok"

# 7) O Kit da NeverMiss (contrato, 17.3 e 17.4), com um jogador de verdade: a armadura de diamante no corpo, a espada com
#    Afiação V e 64 maçãs douradas. O kit da compra conta como o 1º /kit; o próximo só sai 15 s depois. O Folia e o proxy não dão kit.
KIT=
if [ -z "$PROXY" ] && [ "$PLATAFORMA" != folia ]; then KIT=1; fi
desde() { # desde <versão> <a.b.c>: a versão do servidor é essa ou mais nova
  python3 -c 'import sys; f=lambda t: [int(x) for x in (t.split(".") + ["0", "0"])[:3]]; sys.exit(0 if f(sys.argv[1]) >= f(sys.argv[2]) else 1)' "$1" "$2"
}
jogador() { # jogador <nick> <fd>: entra com o cliente falso; o que for escrito no fd é comando dele
  rm -f "$DIR/$1.fifo" && mkfifo "$DIR/$1.fifo"
  nice python3 -I "$RAIZ/teste/cliente.py" "$PORTA_MC" "$1" < "$DIR/$1.fifo" > "$DIR/$1.log" 2>&1 &
  PIDS="$PIDS $!"
  eval "exec $2> \"$DIR/$1.fifo\""
  for _ in $(seq 60); do grep -q CONECTADO "$DIR/$1.log" && return 0; grep -q SAIU "$DIR/$1.log" && break; sleep 1; done
  falha "o jogador $1 não entrou: $(cat "$DIR/$1.log")"
}
chat() { # chat <nick> <texto> <vezes> <segundos>: o jogador recebeu a mensagem N vezes
  for _ in $(seq "$4"); do [ "$(grep -c -- "CHAT .*$2" "$DIR/$1.log")" -ge "$3" ] && return 0; sleep 1; done
  falha "$1 não recebeu '$2' ($3 vez(es)): $(tail -5 "$DIR/$1.log")"
}
# inventario <maçãs> <capacetes>: o que o Kiteiro tem, pelo próprio jogo (data get no 1.13+, testfor antes).
# O 1.7.10 não tem o testfor com NBT nem o clear que só conta: lá a prova é o log do plugin e a mensagem ao jogador.
inventario() {
  if [ "$VERSAO" = 1.7.10 ]; then echo "1.7.10: o inventário não é conferido pelo jogo"; return 0; fi
  : > "$DIR/inventario.txt"
  MARCA=$(wc -l < "$DIR/saida.log")
  if antigo "$VERSAO"; then
    console 'testfor Kiteiro {Inventory:[{Slot:103b,id:"minecraft:diamond_helmet"},{Slot:102b,id:"minecraft:diamond_chestplate"},{Slot:101b,id:"minecraft:diamond_leggings"},{Slot:100b,id:"minecraft:diamond_boots"},{Slot:0b,id:"minecraft:diamond_sword",tag:{ench:[{id:16s,lvl:5s}]}},{Slot:1b,id:"minecraft:golden_apple",Count:64b}]}'
    console "clear Kiteiro minecraft:golden_apple -1 0"
    console "clear Kiteiro minecraft:diamond_helmet -1 0"
  else
    console "data get entity Kiteiro Inventory"
    if desde "$VERSAO" 1.21.5; then console "data get entity Kiteiro equipment"; fi
    console "clear Kiteiro minecraft:golden_apple 0"
    console "clear Kiteiro minecraft:diamond_helmet 0"
  fi
  sleep 3
  tail -n +"$((MARCA + 1))" "$DIR/saida.log" > "$DIR/inventario.txt"
  python3 - "$DIR/inventario.txt" "$VERSAO" "$1" "$2" <<'PY' || falha "o inventário do Kiteiro não bate: $(cat "$DIR/inventario.txt")"
import re, sys
log, versao, macas, capacetes = open(sys.argv[1], encoding="utf-8", errors="replace").read(), sys.argv[2], sys.argv[3], sys.argv[4]
v = [int(x) for x in (versao.split(".") + ["0"])[:3]]
def tem(padrao):
    assert re.search(padrao, log, re.S), padrao
if v < [1, 13, 0]:
    tem(r"Found Kiteiro")
    tem(r"Kiteiro has %s items" % macas)
    tem(r"Kiteiro has %s items" % capacetes)
else:
    if v < [1, 21, 5]:
        for slot, peca in ((100, "boots"), (101, "leggings"), (102, "chestplate"), (103, "helmet")):
            tem(r'Slot: %db, id: "minecraft:diamond_%s"' % (slot, peca))
        tem(r'Slot: 0b, id: "minecraft:diamond_sword"')
    else:
        for slot, peca in (("feet", "boots"), ("legs", "leggings"), ("chest", "chestplate"), ("head", "helmet")):
            tem(r'%s: \{[^}]*id: "minecraft:diamond_%s"' % (slot, peca))
    tem(r'"minecraft:sharpness"' + (r"\W+5" if v >= [1, 20, 5] else r", lvl: 5s"))
    tem(r"mina VIP")
    tem(r"Found %s matching item.{0,4} on player Kiteiro" % macas)
    tem(r"Found %s matching item.{0,4} on player Kiteiro" % capacetes)
PY
}
if [ -n "$KIT" ]; then
  jogador Kiteiro 5
  entrega "$(cat <<'JSON'
{"id":"mckit","nick":"Kiteiro","soOnline":true,"comandos":[],"acoes":[{"tipo":"kit","darAgora":true,"vezes":1,
 "kit":{"id":"kitvip","nome":"VIP Diamante","chave":"vip-diamante","equipar":true,"itens":[
  {"espaco":0,"item":"diamond_sword","itemLegado":"DIAMOND_SWORD","qtd":1,"encantamentos":[{"id":"sharpness","idLegado":"DAMAGE_ALL","nivel":5}],
   "nome":"&bLâmina VIP","descricao":"Só para VIP"},
  {"espaco":1,"item":"golden_apple","itemLegado":"GOLDEN_APPLE","qtd":64,"encantamentos":[]},
  {"espaco":36,"item":"diamond_boots","itemLegado":"DIAMOND_BOOTS","qtd":1,"encantamentos":[]},
  {"espaco":37,"item":"diamond_leggings","itemLegado":"DIAMOND_LEGGINGS","qtd":1,"encantamentos":[]},
  {"espaco":38,"item":"diamond_chestplate","itemLegado":"DIAMOND_CHESTPLATE","qtd":1,"encantamentos":[]},
  {"espaco":39,"item":"diamond_helmet","itemLegado":"DIAMOND_HELMET","qtd":1,"encantamentos":[]}]},
 "resgate":{"intervaloS":15,"ate":null}}]}
JSON
)"
  espera mckit "entregue" 40
  DEU=$(date +%s)
  chat Kiteiro "recebeu o kit VIP Diamante. Use /kit vip-diamante a cada 15 s" 1 10
  inventario 64 1
  echo "kit vip-diamante" >&5
  chat Kiteiro "Espere .* para pegar o kit VIP Diamante de novo" 1 10
  echo "kit" >&5
  chat Kiteiro "Seus kits: vip-diamante (em " 1 10
  jogador Curioso 6
  echo "kit vip-diamante" >&6
  chat Curioso "o tem esse kit" 1 10
  FALTA=$((DEU + 16 - $(date +%s)))
  [ "$FALTA" -gt 0 ] && sleep "$FALTA"
  echo "nmkit vip-diamante" >&5
  chat Kiteiro "pegou o kit VIP Diamante" 1 10
  echo "kit vip-diamante" >&5
  chat Kiteiro "Espere .* para pegar o kit VIP Diamante de novo" 2 10
  # A armadura do 2º kit não cabe no corpo: vai para o inventário
  inventario 128 2
  noLog "Kiteiro pegou o kit VIP Diamante (/kit, 6 itens)" || falha "o /kit não deu os 6 itens"
  [ "$(grep -c '^mckit ' "$DADOS/resgates.txt")" = 2 ] || falha "o resgates.txt não tem a compra e o /kit"
  grep -q "CHAT .*pegou" "$DIR/Curioso.log" && falha "quem não comprou pegou o kit"
  echo "Kit e /kit: ok"
fi

console "nevermiss status"
console "nevermiss testar"
if [ -z "$PROXY" ] && [ "$PLATAFORMA" != folia ]; then
  if antigo "$VERSAO"; then console "scoreboard players list Steve"; else console "scoreboard players get Steve vip"; fi
fi
sleep 8

[ "$(grep -c "Rodou: $OK" "$DIR/saida.log")" = 2 ] || falha "o comando do Steve não rodou exatamente 2 vezes"
if [ "$PLATAFORMA" = folia ]; then
  [ "$(grep -c '\[Server\] Steve comprou vip' "$DIR/saida.log")" = 2 ] || falha "o say do Steve não saiu exatamente 2 vezes"
elif [ -z "$PROXY" ]; then
  if antigo "$VERSAO"; then noLog 'vip: 2' || falha "placar do Steve não é 2"; else noLog 'Steve has 2 \[vip\]' || falha "placar do Steve não é 2"; fi
fi
noLog 'Teste do painel recebido' || falha "teste do painel não apareceu"
noLog "Rodou: $BLOQ" && falha "comando de entrega bloqueada rodou"
noLog 'Rodou: .*Interrompido' && falha "a entrega interrompida rodou"
noLog '\[NeverMiss\] Conectado' || falha "/nevermiss testar não respondeu"
noLog "NeverMiss $VERSAO_PLUGIN ($ESPERA)" || falha "/nevermiss status não mostrou a plataforma $ESPERA"
if [ -n "$PROXY" ]; then noLog "Rodou: glist $NOTCH" || falha "o {uuid} do proxy não veio da Mojang"; fi

# O que o plugin manda para a API: plataforma, edição e o Floodgate.
PULL=$(curl -fsS "$API/teste/estado" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["ultimoPull"], sort_keys=True))')
echo "Servidor no pull: $PULL"
RECURSOS=$(curl -fsS "$API/teste/estado" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["recursos"]))')
echo "Recursos no pull: $RECURSOS"
python3 - "$PULL" "$ESPERA" "$BEDROCK" "$PROXY" "$RECURSOS" "$KIT" <<'PY' || falha "o pull não traz a plataforma, a edição, o bedrock ou os recursos certos"
import json, sys
p, plataforma, bedrock, proxy = json.loads(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
assert json.loads(sys.argv[5]) == (["kit"] if sys.argv[6] else []), sys.argv[5]
assert p["plataforma"] == plataforma and p["edicao"] == "java", p
assert p["bedrock"] == ({"floodgate": True, "prefixo": "."} if bedrock else None), p
assert p["onlineMode"] == bool(proxy), p
PY
if [ -n "$BEDROCK" ]; then
  noLog 'Floodgate encontrado (prefixo ".")' || falha "o Floodgate não foi detectado"
  noLog "Rodou: $BEDROCK_RODOU" || falha "a entrega Bedrock não trocou o gamertag pelo nome do Floodgate"
else
  noLog 'este servidor não aceita Bedrock: instale Geyser + Floodgate' || falha "a entrega Bedrock sem Floodgate não avisou"
fi
if [ -n "$PROXY" ]; then
  noLogDe "$FUNDO" 'Rodou: say coexiste' || falha "o plugin do Paper atrás do proxy não entregou"
  grep -rq "$CHAVE2" "$FUNDO/saida.log" "$FUNDO/logs" 2>/dev/null && falha "a chave do Paper apareceu no log"
fi
grep -rq "$CHAVE" "$DIR/saida.log" "$DIR/logs" 2>/dev/null && falha "a chave apareceu no log"

console "$PARAR"
wait "$PID_MC" || true
exec 3>&-
if [ -n "$PROXY" ]; then
  echo "stop" >&4
  wait "$PID_FUNDO" || true
  exec 4>&-
fi
echo "TUDO OK ($PLATAFORMA $VERSAO${BEDROCK:+ com Geyser/Floodgate}${PROXY:+ + Paper $BACKEND atrás})"
