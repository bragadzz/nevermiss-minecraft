#!/usr/bin/env bash
# Teste de ponta a ponta do servidor Bedrock: um PowerNukkitX 3 de verdade com o plugin, falando com a API falsa.
# Uso: teste/e2e-nukkit.sh <versão do PowerNukkitX> (ou teste/e2e.sh powernukkitx <versão>), ex.: 3.0.6
# Um jogador Bedrock falso (teste/ClienteBedrock.java, com a biblioteca de protocolo do próprio PowerNukkitX) entra
# com o gamertag "Cool Guy", com espaço, sem conta Xbox (xbox-auth desligado).
# JAVA=/caminho/do/java do JDK 25 (o PowerNukkitX 3 pede o 25); o jar do plugin compila com ele (JAVA25_HOME).
set -euo pipefail

VERSAO=${1:?"Uso: teste/e2e-nukkit.sh <versão do PowerNukkitX>"}
RAIZ=$(cd "$(dirname "$0")/.." && pwd)
DIR="$RAIZ/teste/servidores/powernukkitx-$VERSAO"
PORTA_API=${PORTA_API:-3429}
PORTA_MC=${PORTA_MC:-19399}
JAVA=${JAVA:-java}
JAVA25_HOME=${JAVA25_HOME:-$(cd "$(dirname "$(command -v "$JAVA")")/.." && pwd)}
export JAVA25_HOME
API="http://127.0.0.1:$PORTA_API"
CHAVE="nmmc_$(printf 'e%.0s' $(seq 43))"
VERSAO_PLUGIN=$(sed -n "s/^ *version = '\(.*\)'/\1/p" "$RAIZ/build.gradle")
GAMERTAG="Cool Guy"

falha() {
  echo "FALHOU: $*"
  echo "--- fim do log ---"; tail -40 "$DIR/saida.log" 2>/dev/null || true
  echo "--- jogador ---"; tail -10 "$DIR/jogador.log" 2>/dev/null || true
  exit 1
}

# 1) O jar e a API falsa (o build também roda os testes do core).
(cd "$RAIZ" && ./gradlew -q :core:test :nukkit:shadowJar)
GSON=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name 'gson-2.10.1.jar' | head -1)
CP="$RAIZ/core/build/classes/java/test:$RAIZ/core/build/classes/java/main:$GSON"

# 2) O servidor, da release oficial do PowerNukkitX no GitHub, com o sha256 que o GitHub publica.
mkdir -p "$DIR"
if [ ! -f "$DIR/servidor.jar" ]; then
  read -r URL SHA < <(curl -fsS "https://api.github.com/repos/PowerNukkitX/PowerNukkitX/releases/tags/$VERSAO" |
    python3 -c 'import json,sys; a=[x for x in json.load(sys.stdin)["assets"] if x["name"]=="powernukkitx.jar"][0]; print(a["browser_download_url"], a["digest"].split(":")[1])')
  curl -fsSL -o "$DIR/servidor.jar.tmp" "$URL"
  echo "$SHA  $DIR/servidor.jar.tmp" | shasum -a 256 -c - >/dev/null
  mv "$DIR/servidor.jar.tmp" "$DIR/servidor.jar"
fi

# 3) A configuração: só em 127.0.0.1 (o NetherNet, o transporte do PowerNukkitX desde o 3.0.5, com a sinalização em
#    tcp/<porta> e o WebRTC em udp/<porta-1>, sem STUN), sem conta Xbox, sem estatística (bStats) e sem o relatório de erro (Sentry).
rm -rf "$DIR/worlds" "$DIR/players" "$DIR/logs" "$DIR/plugins" "$DIR/pnx.yml" "$DIR/keys"
mkdir -p "$DIR/plugins/NeverMiss"
printf 'settings:\n  ip: 127.0.0.1\n  port: %s\n  xboxAuth: false\n  language: eng\nmisc-settings:\n  enableMetrics: false\n  installSpark: false\nnetwork-settings:\n  enableQuery: false\n  nethernet:\n    udpPort: %s\n    stun-servers: []\n' "$PORTA_MC" "$((PORTA_MC - 1))" > "$DIR/pnx.yml"
printf 'chave: "%s"\napi: "%s"\n' "$CHAVE" "$API" > "$DIR/plugins/NeverMiss/config.yml"
cp "$RAIZ/nukkit/build/libs/NeverMiss-PowerNukkitX-"*.jar "$DIR/plugins/"
# Uma entrega que "estava rodando" quando o servidor caiu (T15).
echo "mcinterrompida rodando $(($(date +%s) * 1000))" > "$DIR/plugins/NeverMiss/executadas.txt"

# 4) Sobe a API falsa (lease 5 s, próxima consulta 10 s) e o servidor. O console é um fifo.
PIDS=""
# O PowerNukkitX nem sempre sai com o SIGTERM: depois de 3 s, o -9 (só nos processos deste teste).
trap 'kill $PIDS 2>/dev/null || true; sleep 3; kill -9 $PIDS 2>/dev/null || true' EXIT
nice "$JAVA" -cp "$CP" com.nevermissapps.minecraft.ApiFalsa "$PORTA_API" "$CHAVE" 5000 10 > "$DIR/api.log" 2>&1 &
PIDS="$PIDS $!"
rm -f "$DIR/console.fifo" && mkfifo "$DIR/console.fifo"
(cd "$DIR" && exec nice "$JAVA" -Xmx1536M -DdisableSentry=true -jar servidor.jar --disable-ansi < console.fifo > saida.log 2>&1) &
PID_MC=$!
PIDS="$PIDS $PID_MC"
exec 3> "$DIR/console.fifo"
console() { echo "$*" >&3; }
noLog() { grep -q -- "$1" "$DIR/saida.log"; }

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

sleep 2
curl -fsS "$API/teste/estado" >/dev/null || falha "a API falsa não subiu"
for _ in $(seq 300); do noLog 'Done (' && break; kill -0 "$PID_MC" 2>/dev/null || falha "o PowerNukkitX não subiu"; sleep 1; done
noLog 'Done (' || falha "o PowerNukkitX não terminou de ligar"
echo "PowerNukkitX $VERSAO no ar."

# 5) As entregas. O nick do Bedrock é o gamertag: com espaço, vai entre aspas no comando.
OK="say Steve comprou vip"
entrega '{"id":"mcinterrompida","nick":"Interrompido","comandos":["say Interrompido comprou vip"]}'
entrega "{\"id\":\"mc1\",\"nick\":\"Steve\",\"comandos\":[\"$OK\"]}"
entrega '{"id":"mcbloqueada","nick":"Steve","comandos":["say isto nao pode rodar","op Steve"]}'
entrega '{"id":"mcdesligar","nick":"Steve","comandos":["say isto nao pode rodar","end"]}'
entrega '{"id":"mcsufixo","nick":"Cool Guy#1234","edicaoJogador":"bedrock","comandos":["say {nick}"]}'
# A lista proibida vale para o comando já montado: o gamertag "Stop Now" vira a 1ª palavra.
entrega '{"id":"mcmontado","nick":"Stop Now","edicaoJogador":"bedrock","comandos":["{nick} agora"]}'
entrega '{"id":"mcuuid","nick":"Steve","comandos":["say {uuid}"]}'
entrega '{"id":"mcteste","evento":"teste","comandos":[]}'
entrega '{"id":"mcfalha","nick":"Steve","comandos":["comandoquenaoexiste Steve"]}'
# Só com o jogador online: espera o Cool Guy entrar.
entrega '{"id":"mcbedrock","nick":"Cool Guy","edicaoJogador":"bedrock","soOnline":true,"comandos":["give {nick} diamond 3","tell {nick} chegou o seu vip"]}'
entrega '{"id":"mcausente","nick":"Ninguem Aqui","edicaoJogador":"bedrock","soOnline":true,"comandos":["say {nick}"]}'

espera mcinterrompida "falhou interrompida" 40
espera mc1 "entregue" 40
espera mcbloqueada "falhou bloqueado" 5
espera mcdesligar "falhou bloqueado" 5
espera mcsufixo "falhou bloqueado" 5
espera mcmontado "falhou bloqueado" 5
espera mcuuid "falhou uuid_pirata" 5
espera mcteste "entregue" 5
espera mcfalha "falhou comando_falhou" 5
espera mcbedrock "pendente" 1
sleep 12
espera mcbedrock "pendente None 0" 1
noLog 'Rodou: give' && falha "a entrega só com o jogador online rodou com ele fora"
echo "T13/T15/T17/T19/T21: ok"

# 6) O jogador Bedrock entra: a entrega que esperava sai na hora, com o gamertag entre aspas.
rm -f "$DIR/jogador.fifo" && mkfifo "$DIR/jogador.fifo"
# Numa pasta só dele: o log4j que vem no jar do PowerNukkitX cria o logs/ onde roda.
mkdir -p "$DIR/jogador"
(cd "$DIR/jogador" && exec nice "$JAVA" -cp "$DIR/servidor.jar" "$RAIZ/teste/ClienteBedrock.java" "$PORTA_MC" "$GAMERTAG" < "$DIR/jogador.fifo" > "$DIR/jogador.log" 2>&1) &
PIDS="$PIDS $!"
exec 4> "$DIR/jogador.fifo"
for _ in $(seq 90); do grep -q CONECTADO "$DIR/jogador.log" && break; grep -q SAIU "$DIR/jogador.log" && break; sleep 1; done
grep -q CONECTADO "$DIR/jogador.log" || falha "o jogador Bedrock não entrou: $(tail -5 "$DIR/jogador.log")"
espera mcbedrock "entregue" 20
noLog 'Rodou: give "Cool Guy" diamond 3' || falha "o give não saiu com o gamertag entre aspas"
for _ in $(seq 10); do grep -q 'CHAT .*chegou o seu vip' "$DIR/jogador.log" && break; sleep 1; done
grep -q 'CHAT .*chegou o seu vip' "$DIR/jogador.log" || falha "o jogador não recebeu o tell: $(tail -5 "$DIR/jogador.log")"
MARCA=$(wc -l < "$DIR/saida.log")
console 'clear "Cool Guy" diamond 0 0'
sleep 2
tail -n +"$((MARCA + 1))" "$DIR/saida.log" | grep -q 'Cool Guy has 3 items' || falha "o Cool Guy não tem os 3 diamantes"
estado mcausente | grep -q '^pendente' || falha "a entrega de quem não entrou saiu"
echo "Bedrock online: ok"

# 7) O Kit da NeverMiss não sai em servidor Bedrock (os itens do painel são do Java): recusa com motivo, nada roda.
entrega "$(cat <<'JSON'
{"id":"mckit","nick":"Cool Guy","edicaoJogador":"bedrock","soOnline":true,"comandos":["say kit {nick}"],"acoes":[{"tipo":"kit","darAgora":true,"vezes":1,
 "kit":{"id":"kitvip","nome":"VIP Diamante","chave":"vip-diamante","equipar":true,"itens":[
  {"espaco":0,"item":"diamond_sword","itemLegado":"DIAMOND_SWORD","qtd":1,"encantamentos":[]}]},"resgate":{"intervaloS":15,"ate":null}}]}
JSON
)"
espera mckit "falhou kit_incompativel" 30
noLog 'Rodou: say kit' && falha "a entrega do kit rodou comando"
noLog 'o Kit da NeverMiss ainda não sai em servidor Bedrock' || falha "o console não explicou o kit recusado"
echo "Kit recusado: ok"

# 8) Confirmação perdida 2 vezes (T14): volta pelo lease, o plugin não roda de novo.
curl -fsS -X POST "$API/teste/cortar" >/dev/null
curl -fsS -X POST "$API/teste/cortar" >/dev/null
entrega "{\"id\":\"mc2\",\"nick\":\"Steve\",\"comandos\":[\"$OK\"]}"
espera mc2 "entregue None 2" 60
echo "T14: ok"

console "nevermiss status"
console "nevermiss testar"
sleep 8

[ "$(grep -c "Rodou: $OK" "$DIR/saida.log")" = 2 ] || falha "o comando do Steve não rodou exatamente 2 vezes"
[ "$(grep -c 'Steve comprou vip' "$DIR/saida.log")" -ge 4 ] || falha "o say do Steve não saiu no jogo 2 vezes"
noLog 'Teste do painel recebido' || falha "teste do painel não apareceu"
noLog 'Rodou: say isto nao pode rodar' && falha "comando de entrega bloqueada rodou"
noLog 'Rodou: .*Interrompido' && falha "a entrega interrompida rodou"
noLog '\[NeverMiss\] Conectado' || falha "/nevermiss testar não respondeu"
noLog "NeverMiss $VERSAO_PLUGIN (nukkit)" || falha "/nevermiss status não mostrou a plataforma"
noLog 'Bedrock: servidor Bedrock (o nome do jogador é o gamertag)' || falha "/nevermiss status não mostrou o Bedrock"

# O que o plugin manda para a API: plataforma, edição, sem Floodgate e sem kit.
PULL=$(curl -fsS "$API/teste/estado" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["ultimoPull"], sort_keys=True))')
echo "Servidor no pull: $PULL"
RECURSOS=$(curl -fsS "$API/teste/estado" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["recursos"]))')
echo "Recursos no pull: $RECURSOS"
python3 - "$PULL" "$RECURSOS" <<'PY' || falha "o pull não traz a plataforma, a edição, o bedrock ou os recursos certos"
import json, sys
p, r = json.loads(sys.argv[1]), json.loads(sys.argv[2])
assert r == [], r
assert p["plataforma"] == "nukkit" and p["edicao"] == "bedrock" and p["bedrock"] is None and p["onlineMode"] is False, p
assert "PowerNukkitX" in p["versao"], p
PY
grep -rq "$CHAVE" "$DIR/saida.log" "$DIR/logs" 2>/dev/null && falha "a chave apareceu no log"

exec 4>&-
console "stop"
wait "$PID_MC" || true
exec 3>&-
echo "TUDO OK (PowerNukkitX $VERSAO)"
