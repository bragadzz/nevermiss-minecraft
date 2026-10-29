#!/usr/bin/env bash
# Teste de ponta a ponta: um servidor (ou proxy) de verdade com o plugin, falando com a API falsa.
# Uso: teste/e2e.sh <plataforma> <versão> [bedrock]
#   paper 1.20.4 | paper 1.7.10 | folia 1.21.11 | spigot 1.21.11 (o spigot.jar do BuildTools em teste/servidores/spigot-<versão>/servidor.jar)
#   velocity 3.5.1 | waterfall 1.21 | bungeecord latest: sobe também um Paper atrás do proxy (BACKEND=1.20.4),
#     com o plugin do servidor e outra chave, e confere que os dois entregam juntos.
#   bedrock: instala o Floodgate (e o Geyser, no servidor) e entrega para um jogador Bedrock.
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

case "$PLATAFORMA" in
  paper | spigot | folia) PROXY= ; JAR=bukkit ; PASTA=NeverMiss ; PRONTO='Done (' ; PARAR=stop ;;
  velocity) PROXY=1 ; JAR=velocity ; PASTA=nevermiss ; PRONTO='Done (' ; PARAR=shutdown ;;
  waterfall | bungeecord) PROXY=1 ; JAR=bungee ; PASTA=NeverMiss ; PRONTO='Listening on ' ; PARAR=end ;;
  *) echo "Plataforma desconhecida: $PLATAFORMA"; exit 2 ;;
esac
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
  printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=%s\nlevel-type=flat\ngenerate-structures=false\nspawn-protection=0\nenforce-secure-profile=false\n' "$2" > "$1/server.properties"
  printf 'enabled: false\nserverUuid: 00000000-0000-0000-0000-000000000000\nlogFailedRequests: false\n' > "$1/plugins/bStats/config.yml"
  printf 'chave: "%s"\napi: "%s"\n' "$3" "$4" > "$1/plugins/NeverMiss/config.yml"
  cp "$RAIZ/bukkit/build/libs/NeverMiss-Bukkit-"*.jar "$1/plugins/"
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
if [ -z "$PROXY" ]; then
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
echo "mcinterrompida rodando $(($(date +%s) * 1000))" > "$DIR/plugins/$PASTA/executadas.txt"

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
noLog "NeverMiss 1.1.0 ($ESPERA)" || falha "/nevermiss status não mostrou a plataforma $ESPERA"
if [ -n "$PROXY" ]; then noLog "Rodou: glist $NOTCH" || falha "o {uuid} do proxy não veio da Mojang"; fi

# O que o plugin manda para a API: plataforma, edição e o Floodgate.
PULL=$(curl -fsS "$API/teste/estado" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["ultimoPull"], sort_keys=True))')
echo "Servidor no pull: $PULL"
python3 - "$PULL" "$ESPERA" "$BEDROCK" "$PROXY" <<'PY' || falha "o pull não traz a plataforma, a edição ou o bedrock certos"
import json, sys
p, plataforma, bedrock, proxy = json.loads(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
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
