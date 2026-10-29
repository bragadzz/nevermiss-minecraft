#!/usr/bin/env bash
# Teste de ponta a ponta: um Paper de verdade com o plugin, falando com a API falsa.
# Uso: teste/e2e.sh [1.20.4|1.8.8]   (JAVA=/caminho/do/java para escolher o Java)
set -euo pipefail

VERSAO=${1:-1.20.4}
RAIZ=$(cd "$(dirname "$0")/.." && pwd)
DIR="$RAIZ/teste/servidores/$VERSAO"
PORTA_API=${PORTA_API:-3409}
PORTA_MC=${PORTA_MC:-25599}
JAVA=${JAVA:-java}
API="http://127.0.0.1:$PORTA_API"
CHAVE="nmmc_$(printf 'e%.0s' $(seq 43))"

falha() { echo "FALHOU: $*"; echo "--- fim do log ---"; tail -40 "$DIR/saida.log" 2>/dev/null || true; exit 1; }

# 1) O plugin e a API falsa (o build também roda os testes).
(cd "$RAIZ" && ./gradlew -q build)
GSON=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name 'gson-2.2.4.jar' | head -1)

# 2) O Paper, da PaperMC, com o sha256 conferido.
mkdir -p "$DIR/plugins/NeverMiss" "$DIR/plugins/bStats"
if [ ! -f "$DIR/paper.jar" ]; then
  read -r URL SHA < <(curl -fsS "https://fill.papermc.io/v3/projects/paper/versions/$VERSAO/builds/latest" |
    python3 -c 'import json,sys; d=json.load(sys.stdin)["downloads"]["server:default"]; print(d["url"], d["checksums"]["sha256"])')
  curl -fsSL -o "$DIR/paper.jar.tmp" "$URL"
  echo "$SHA  $DIR/paper.jar.tmp" | shasum -a 256 -c - >/dev/null
  mv "$DIR/paper.jar.tmp" "$DIR/paper.jar"
fi

# 3) Servidor pirata, só em 127.0.0.1, sem estatística (bStats).
rm -rf "$DIR/world" "$DIR/world_nether" "$DIR/world_the_end" "$DIR/logs" "$DIR/plugins/NeverMiss"
mkdir -p "$DIR/plugins/NeverMiss"
echo "eula=true" > "$DIR/eula.txt"
printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=%s\nlevel-type=flat\ngenerate-structures=false\nspawn-protection=0\nenforce-secure-profile=false\n' "$PORTA_MC" > "$DIR/server.properties"
printf 'enabled: false\nserverUuid: 00000000-0000-0000-0000-000000000000\nlogFailedRequests: false\n' > "$DIR/plugins/bStats/config.yml"
printf 'chave: "%s"\napi: "%s"\n' "$CHAVE" "$API" > "$DIR/plugins/NeverMiss/config.yml"
cp "$RAIZ/build/libs/NeverMiss-"*.jar "$DIR/plugins/"
# Uma entrega que "estava rodando" quando o servidor caiu (T15).
echo "mcinterrompida rodando $(($(date +%s) * 1000))" > "$DIR/plugins/NeverMiss/executadas.txt"

# 4) Sobe a API falsa (lease 5 s, próxima consulta 10 s) e o servidor.
nice "$JAVA" -cp "$RAIZ/build/classes/java/test:$RAIZ/build/classes/java/main:$GSON" \
  com.nevermissapps.minecraft.ApiFalsa "$PORTA_API" "$CHAVE" 5000 10 > "$DIR/api.log" 2>&1 &
PID_API=$!
rm -f "$DIR/console.fifo" && mkfifo "$DIR/console.fifo"
(cd "$DIR" && exec nice "$JAVA" -Xmx1G -jar paper.jar nogui < console.fifo > saida.log 2>&1) &
PID_MC=$!
trap 'kill $PID_MC $PID_API 2>/dev/null || true' EXIT
exec 3> "$DIR/console.fifo"
console() { echo "$*" >&3; }

entrega() { curl -fsS -X POST "$API/teste/entregas" -d "$1" >/dev/null; }
estado() {
  curl -fsS "$API/teste/estado" | python3 -c 'import json,sys; e=json.load(sys.stdin)["entregas"].get(sys.argv[1]) or {}; print(e.get("estado"), e.get("motivo"), e.get("tentativas"))' "$1"
}
espera() { # espera <id> <"estado motivo"> <segundos>
  for _ in $(seq "$3"); do
    case "$(estado "$1")" in "$2"*) return 0 ;; esac
    sleep 1
  done
  falha "$1 ficou em '$(estado "$1")', esperava '$2'"
}
noLog() { grep -q -- "$1" "$DIR/saida.log"; }

sleep 2
curl -fsS "$API/teste/estado" >/dev/null || falha "a API falsa não subiu"
for _ in $(seq 300); do noLog 'Done (' && break; kill -0 $PID_MC 2>/dev/null || falha "o servidor não subiu"; sleep 1; done
noLog 'Done (' || falha "o servidor não terminou de ligar"
echo "Paper $VERSAO no ar."

# 5) As entregas.
entrega '{"id":"mcinterrompida","nick":"Interrompido","comandos":["scoreboard players add Interrompido vip 1"]}'
console "scoreboard objectives add vip dummy"
sleep 1
entrega '{"id":"mc1","nick":"Steve","comandos":["scoreboard players add Steve vip 1"]}'
entrega '{"id":"mcbloqueada","nick":"Steve","comandos":["say isto nao pode rodar","op Steve"]}'
entrega '{"id":"mcuuid","nick":"Steve","comandos":["scoreboard players add {uuid} vip 1"]}'
entrega '{"id":"mcteste","evento":"teste","comandos":[]}'
entrega '{"id":"mcfalha","nick":"Steve","comandos":["comandoquenaoexiste Steve"]}'

espera mcinterrompida "falhou interrompida" 40
espera mc1 "entregue" 40
espera mcbloqueada "falhou bloqueado" 5
espera mcuuid "falhou uuid_pirata" 5
espera mcteste "entregue" 5
espera mcfalha "falhou comando_falhou" 5
echo "T13/T15/T17/T19/T21: ok"

# 6) Confirmação perdida 2 vezes (T14): volta pelo lease, o plugin não roda de novo.
curl -fsS -X POST "$API/teste/cortar" >/dev/null
curl -fsS -X POST "$API/teste/cortar" >/dev/null
entrega '{"id":"mc2","nick":"Steve","comandos":["scoreboard players add Steve vip 1"]}'
espera mc2 "entregue None 2" 60
echo "T14: ok"

console "nevermiss status"
console "nevermiss testar"
if [ "$VERSAO" = "1.8.8" ]; then console "scoreboard players list Steve"; else console "scoreboard players get Steve vip"; fi
sleep 8

[ "$(grep -c 'Rodou: scoreboard players add Steve vip 1' "$DIR/saida.log")" = 2 ] || falha "o comando do Steve não rodou exatamente 2 vezes"
if [ "$VERSAO" = "1.8.8" ]; then noLog 'vip: 2' || falha "placar do Steve não é 2"; else noLog 'Steve has 2 \[vip\]' || falha "placar do Steve não é 2"; fi
noLog 'Teste do painel recebido' || falha "teste do painel não apareceu"
noLog 'isto nao pode rodar' && grep 'isto nao pode rodar' "$DIR/saida.log" | grep -qv 'comando proibido' && falha "comando de entrega bloqueada rodou"
noLog 'Rodou: scoreboard players add Interrompido' && falha "a entrega interrompida rodou"
noLog '\[NeverMiss\] Conectado' || falha "/nevermiss testar não respondeu"
grep -q "$CHAVE" "$DIR/saida.log" "$DIR/logs/latest.log" && falha "a chave apareceu no log"

console "stop"
wait $PID_MC || true
exec 3>&-
echo "TUDO OK (Paper $VERSAO)"
