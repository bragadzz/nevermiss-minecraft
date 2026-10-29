#!/usr/bin/env bash
# Teste de ponta a ponta: um PocketMine-MP 5 de verdade com o plugin, falando com a API falsa. Termina com "TUDO OK".
# Uso: teste/e2e.sh   (PHP=/caminho/do/php para escolher o PHP; sem ele, usa o do pmmp)
# Portas: API falsa 3971 (TCP), servidor 19132 e 19133 (UDP), só em 127.0.0.1 e ::1.
set -euo pipefail

RAIZ=$(cd "$(dirname "$0")/.." && pwd)
DIR="$RAIZ/teste/servidor"
API="http://127.0.0.1:3971"
CHAVE="nmmc_$(printf 'e%.0s' $(seq 43))"

falha() { echo "FALHOU: $*"; echo "--- fim do log ---"; tail -40 "$DIR/saida.log" 2>/dev/null || true; exit 1; }

# 1) O phar e o teste do laço (como o gradle build do plugin Java).
PHP=${PHP:-$("$RAIZ/teste/baixar.sh")}
PM="$RAIZ/teste/baixados/PocketMine-MP.phar"
INI="$(dirname "$PHP")/php.ini"
PHP_INI=()
[ -f "$INI" ] && PHP_INI=(-c "$INI")
nice node "$RAIZ/build.mjs"
PHP="$PHP" "$RAIZ/teste/unitario.sh"

# 2) Servidor limpo, só no 127.0.0.1, sem xbox-auth (o {uuid} dá uuid_pirata), sem atualização, estatística nem relatório de erro.
rm -rf "$DIR"
mkdir -p "$DIR/plugins" "$DIR/plugin_data/NeverMiss"
cat > "$DIR/server.properties" <<PROP
motd=NeverMiss teste
server-ip=127.0.0.1
server-ipv6=::1
server-port=19132
server-portv6=19133
enable-ipv6=on
xbox-auth=off
white-list=on
enable-query=off
max-players=5
level-type=FLAT
PROP
cat > "$DIR/pocketmine.yml" <<YML
auto-updater:
  enabled: false
anonymous-statistics:
  enabled: false
auto-report:
  enabled: false
YML
printf 'chave: "%s"\napi: "%s"\n' "$CHAVE" "$API" > "$DIR/plugin_data/NeverMiss/config.yml"
cp "$RAIZ/build/NeverMiss-PocketMine-"*.phar "$DIR/plugins/"
# Uma entrega que "estava rodando" quando o servidor caiu (T15).
echo "mcinterrompida rodando $(($(date +%s) * 1000))" > "$DIR/plugin_data/NeverMiss/executadas.txt"

# 3) A API falsa (lease 5 s, próxima consulta 10 s, 5 s entre consultas) e o servidor.
nice node "$RAIZ/teste/api-falsa.mjs" 3971 "$CHAVE" 5000 10 5000 > "$DIR/api.log" 2>&1 &
PID_API=$!
mkfifo "$DIR/console.fifo"
(cd "$DIR" && exec nice "$PHP" ${PHP_INI[@]+"${PHP_INI[@]}"} "$PM" --no-wizard --disable-ansi --data="$DIR" --plugins="$DIR/plugins" < console.fifo > saida.log 2>&1) &
PID_PM=$!
trap 'kill $PID_PM $PID_API 2>/dev/null || true' EXIT
exec 3> "$DIR/console.fifo"
console() { echo "$*" >&3; }

controle() { curl -fsS -X POST "$API$1" -d "${2-}" > /dev/null; }
estado() { # "estado motivo tentativas" da entrega
  curl -fsS -X POST "$API/teste/estado" | node -e 'let t="";process.stdin.on("data",d=>t+=d).on("end",()=>{const e=JSON.parse(t).entregas[process.argv[1]]||{};console.log(`${e.estado} ${e.motivo} ${e.tentativas}`)})' "$1"
}
espera() { # espera <id> <"estado motivo tentativas" (o começo basta)> <segundos>
  for _ in $(seq "$3"); do
    case "$(estado "$1")" in "$2"*) return 0 ;; esac
    sleep 1
  done
  falha "$1 ficou em '$(estado "$1")', esperava '$2'"
}
noLog() { grep -q -- "$1" "$DIR/saida.log"; }
fora() { grep -v 'Rodou: ' "$DIR/saida.log" | grep -c -- "$1" || true; } # quantas vezes aparece fora do "Rodou:" (o efeito do comando)

for _ in $(seq 30); do curl -fsS -X POST "$API/teste/estado" > /dev/null 2>&1 && break; sleep 1; done
for _ in $(seq 300); do noLog 'Done (' && break; kill -0 $PID_PM 2>/dev/null || falha "o servidor não subiu"; sleep 1; done
noLog 'Done (' || falha "o servidor não terminou de ligar"
noLog 'Chave \*\*\*\*eeee (config.yml)' || falha "o plugin não ligou com a chave"
echo "PocketMine-MP no ar."

# 4) As entregas.
controle /teste/entregas '[
  {"id":"mcinterrompida","nick":"Interrompido","comandos":["say nm-interrompida"]},
  {"id":"mc1","nick":"Steve","comandos":["say nm-e2e-1 Steve"]},
  {"id":"mcbloqueada","nick":"Steve","comandos":["say nm-bloqueada","op Steve"]},
  {"id":"mcvazio","nick":"Steve","comandos":["\"\" op Steve"]},
  {"id":"mcuuid","nick":"Steve","comandos":["say {uuid}"]},
  {"id":"mcteste","evento":"teste","comandos":[]},
  {"id":"mcfalha","nick":"Steve","comandos":["comandoquenaoexiste Steve"]},
  {"id":"mcgamertag","nick":"Some Name#1234","edicaoJogador":"bedrock","comandos":["say nm-gamertag Some_Name"]},
  {"id":"mcespera","nick":"Alex","soOnline":true,"comandos":["say nm-espera Alex"]}
]'
espera mcinterrompida "falhou interrompida" 40
espera mc1 "entregue" 40
espera mcbloqueada "falhou bloqueado" 5
espera mcvazio "falhou bloqueado" 5
espera mcuuid "falhou uuid_pirata" 5
espera mcteste "entregue" 5
espera mcfalha "falhou comando_falhou" 5
espera mcgamertag "entregue" 5
echo "entrega, interrompida, comando proibido, uuid pirata, teste do painel, comando que falha e gamertag: ok"

# 5) Confirmação perdida 2 vezes (T14): volta pelo lease, o plugin não roda de novo.
controle /teste/cortar
controle /teste/cortar
controle /teste/entregas '{"id":"mc2","nick":"Steve","comandos":["say nm-e2e-2 Steve"]}'
espera mc2 "entregue null 2" 60
echo "confirmação perdida: ok"

# 6) Jogador offline: a entrega "só com o jogador online" espera (sem sair, sem contar tentativa) e o plugin sabe dele.
espera mcespera "pendente null 0" 1
console "nevermiss status"
console "nevermiss testar"
sleep 8

[ "$(grep -c 'Rodou: say nm-e2e-' "$DIR/saida.log")" = 2 ] || falha "as entregas do Steve não rodaram exatamente 1 vez cada"
[ "$(fora 'nm-e2e-1 Steve')" = 1 ] && [ "$(fora 'nm-e2e-2 Steve')" = 1 ] || falha "o say do Steve não apareceu exatamente 1 vez no servidor"
[ "$(fora 'nm-gamertag Some Name')" = 1 ] || falha "o gamertag com espaço não chegou como \"Some Name\""
noLog 'Rodou: say nm-gamertag "Some Name"' || falha "o comando do gamertag não saiu entre aspas"
noLog 'Teste do painel recebido' || falha "teste do painel não apareceu"
noLog 'nm-bloqueada' && falha "comando de entrega bloqueada rodou"
noLog 'nm-interrompida' && falha "a entrega interrompida rodou"
noLog 'nm-espera' && falha "a entrega do jogador offline rodou"
noLog 'Esperando o jogador entrar: 1' || falha "/nevermiss status não mostrou o jogador esperando"
noLog '\[NeverMiss\] Conectado' || falha "/nevermiss testar não respondeu"
grep -rq "$CHAVE" "$DIR/saida.log" "$DIR/server.log" && falha "a chave apareceu no log"

console "stop"
wait $PID_PM || true
exec 3>&-
echo "TUDO OK (PocketMine-MP)"
