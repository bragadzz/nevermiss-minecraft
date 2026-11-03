#!/usr/bin/env bash
# Teste de ponta a ponta: o BDS oficial de verdade (na VM do teste/vm-subir.sh) com o addon, falando com a API falsa no Mac.
# Termina com "TUDO OK". Uso: teste/vm-subir.sh && teste/e2e.sh; no fim, teste/vm-apagar.sh.
# Portas: a API falsa na 4011 (TCP) só no 127.0.0.1 do Mac; a VM chega nela pelo 127.0.0.1 dela, por um túnel SSH reverso
# (o addon só aceita http em localhost). O BDS na 19140 e 19141 (UDP), dentro da VM; nenhuma porta da VM vai para o Mac.
# Sem cliente Bedrock, ninguém entra no servidor: o que depende do jogador online (só online, kit, /kit) fica provado pelo
# teste/nucleo-teste.mjs.
set -euo pipefail

RAIZ=$(cd "$(dirname "$0")/.." && pwd)
VM=$HOME/frentes-lojas/mbd-vm
NOME=nmbds
export LIMA_HOME="$VM/lima"
LIMACTL="$VM/lima-bin/bin/limactl"
API="http://127.0.0.1:4011"
CHAVE="nmmc_$(printf 'e%.0s' $(seq 43))"
MODULO=$(node -p 'JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")).modules[0].uuid' "$RAIZ/pacote/manifest.json")
VERSAO=$(node -p 'JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")).header.version.join(".")' "$RAIZ/pacote/manifest.json")
LOGS="$VM/e2e"
mkdir -p "$LOGS"

vm() { "$LIMACTL" shell --workdir /tmp "$NOME" -- bash -c "$1"; }
b64() { printf '%s' "$1" | base64 | tr -d '\n'; }
falha() {
  echo "FALHOU: $*"
  echo "--- fim do log do BDS ---"
  vm "tail -60 ~/bds/saida.log" 2>/dev/null || true
  exit 1
}

# 1) O mcpack e o teste do laço.
nice node "$RAIZ/build.mjs"
nice node "$RAIZ/teste/nucleo-teste.mjs"
"$LIMACTL" list --format '{{.Status}}' "$NOME" 2>/dev/null | grep -qx Running || falha "a VM $NOME não está ligada: rode teste/vm-subir.sh"

# 2) A API falsa (lease 5 s, próxima consulta 10 s, 5 s entre consultas) e o túnel da VM até ela.
nice node "$RAIZ/teste/api-falsa.mjs" 4011 "$CHAVE" 5000 10 5000 > "$LOGS/api.log" 2>&1 &
PID_API=$!
ssh -F "$LIMA_HOME/$NOME/ssh.config" -o ExitOnForwardFailure=yes -N -R 127.0.0.1:4011:127.0.0.1:4011 "lima-$NOME" > "$LOGS/tunel.log" 2>&1 &
PID_TUNEL=$!
trap 'vm "[ -f ~/bds/bds.pid ] && kill \$(cat ~/bds/bds.pid ~/bds/fifo.pid 2>/dev/null) 2>/dev/null" || true; kill $PID_TUNEL $PID_API 2>/dev/null || true' EXIT
for _ in $(seq 30); do curl -fsS -X POST "$API/teste/estado" > /dev/null 2>&1 && break; sleep 1; done
for _ in $(seq 30); do vm "curl -fsS -X POST $API/teste/estado > /dev/null 2>&1" && break; sleep 1; done
vm "curl -fsS -X POST $API/teste/estado > /dev/null" || falha "a VM não chega na API falsa pelo túnel"

controle() { curl -fsS -X POST "$API$1" -d "${2-}" > /dev/null; }
inicios() { # quantas vezes o addon chamou o iniciar da entrega
  curl -fsS -X POST "$API/teste/estado" | node -e 'let t="";process.stdin.on("data",d=>t+=d).on("end",()=>console.log(JSON.parse(t).inicios.filter(i=>i===process.argv[1]).length))' "$1"
}
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
# O log do BDS fica na VM (saida.log, com todas as vezes que ligou). Texto fixo, sem regex.
quantas() { vm "grep -cF -- \"\$(echo $(b64 "$1") | base64 -d)\" ~/bds/saida.log || true"; }
noLog() { [ "$(quantas "$1")" -gt 0 ]; }
esperaLog() { # esperaLog <texto> <segundos> [quantas vezes]
  for _ in $(seq "$2"); do
    [ "$(quantas "$1")" -ge "${3:-1}" ] && return 0
    sleep 2
  done
  return 1
}
console() { vm "echo $(b64 "$1") | base64 -d > ~/bds/console.fifo && echo >> ~/bds/console.fifo"; }
ligar() { # o console do BDS é um FIFO, mantido aberto por um sleep; a saída vai (somando) para o saida.log
  vm 'cd ~/bds && rm -f console.fifo && mkfifo console.fifo
      setsid sleep infinity > console.fifo 2> /dev/null < /dev/null & echo $! > fifo.pid
      LD_LIBRARY_PATH=. setsid nice ./bedrock_server < console.fifo >> saida.log 2>&1 & echo $! > bds.pid'
  esperaLog "Server started." "${1:-150}" "$VEZES" || falha "o BDS não terminou de ligar"
  VEZES=$((VEZES + 1))
}
desligar() { # desligar [kill]: "stop" no console, ou kill -9 (a queda)
  if [ "${1-}" = kill ]; then vm 'kill -9 $(cat ~/bds/bds.pid)'; else console "stop"; fi
  vm 'for _ in $(seq 60); do kill -0 $(cat ~/bds/bds.pid) 2> /dev/null || break; sleep 1; done; ! kill -0 $(cat ~/bds/bds.pid) 2> /dev/null && kill $(cat ~/bds/fifo.pid) 2> /dev/null; true'
}
VEZES=1

# 3) Um BDS limpo, a partir da cópia original. Só no 127.0.0.1 da VM, fora da rede local, com o allowlist ligado.
vm "rm -rf ~/bds && cp -a ~/bds-original ~/bds && cd ~/bds && sed -i \
  -e 's/^server-port=.*/server-port=19140/' -e 's/^server-portv6=.*/server-portv6=19141/' -e 's/^server-udp-ports=.*/server-udp-ports=19140/' \
  -e 's/^level-name=.*/level-name=nevermiss/' -e 's/^enable-lan-visibility=.*/enable-lan-visibility=false/' -e 's/^max-players=.*/max-players=5/' \
  -e 's/^content-log-console-output-enabled=.*/content-log-console-output-enabled=true/' -e 's/^content-log-file-enabled=.*/content-log-file-enabled=true/' \
  server.properties && grep -q '^server-port=19140' server.properties"

# 4) A 1ª vez cria o mundo, sem o addon. Depois, o addon como o README manda: o pack no behavior_packs, o mundo com ele, o
# server-net liberado só para o addon (o permissions.json da pasta dele; o config/default fica como veio) e a chave no
# secrets.json como o painel mostra (sem o Bearer: a API aceita com ou sem).
ligar 300
desligar
CONFIG=$(cat <<SH
set -e
cd ~/bds
mkdir -p behavior_packs/NeverMiss "config/$MODULO"
unzip -oq /mnt/addon/build/NeverMiss-BDS-$VERSAO.mcpack -d behavior_packs/NeverMiss
echo '[{"pack_id": "$(node -p 'require(process.argv[1]).header.uuid' "$RAIZ/pacote/manifest.json")", "version": [${VERSAO//./, }]}]' > worlds/nevermiss/world_behavior_packs.json
echo '{"allowed_modules": ["@minecraft/server", "@minecraft/server-net", "@minecraft/server-admin"]}' > "config/$MODULO/permissions.json"
echo '{"chave": "$CHAVE"}' > "config/$MODULO/secrets.json"
echo '{"api": "$API"}' > "config/$MODULO/variables.json"
SH
)
vm "$CONFIG"

# 5) Liga com o addon. Sem o experimento Beta APIs o server-net (só existe em beta) pode não carregar: o teste confere, liga o
# experimento com o ligar-beta-apis.py (como o README manda) e liga de novo. Se nem assim, o BDS não leu o permissions.json da
# pasta do addon: o server-net vai para o config/default (o caminho B do README) e liga de novo.
ligouAddon() { esperaLog "[NeverMiss] NeverMiss $VERSAO ligado (bds)." "$1"; }
oQueODisse() {
  echo "--- o addon não ligou ($1); o que o BDS disse: ---"
  vm "grep -iE 'script|module|beta|experiment|permission|allowed|nevermiss|error' ~/bds/saida.log | tail -20" || true
}
BETA="não precisou ligar o Beta APIs"
PERM="o permissions.json da pasta do addon bastou"
ligar
if ! ligouAddon 15; then
  oQueODisse "sem o Beta APIs"
  desligar
  vm "python3 -I /mnt/addon/ligar-beta-apis.py ~/bds/worlds/nevermiss/level.dat"
  BETA="precisou ligar o Beta APIs"
  ligar
  if ! ligouAddon 15; then
    oQueODisse "com o Beta APIs e o permissions.json só na pasta do addon"
    desligar
    vm "cd ~/bds && python3 -I -c 'import json,sys; p=sys.argv[1]; d=json.load(open(p)); m=d.setdefault(\"allowed_modules\", []); \"@minecraft/server-net\" in m or m.append(\"@minecraft/server-net\"); json.dump(d, open(p, \"w\"), indent=2)' config/default/permissions.json"
    BETA="o Beta APIs ficou ligado (não dá para saber se precisava)"
    PERM="o BDS não leu o permissions.json da pasta do addon: precisou do config/default"
    ligar
    ligouAddon 30 || falha "o addon não ligou nem com o Beta APIs e o server-net no config/default"
  fi
fi
noLog "[NeverMiss] Chave no secrets.json." || falha "o addon não achou a chave em config/$MODULO/secrets.json"
echo "BDS no ar com o addon ($BETA; $PERM)."

# 6) As entregas. O scoreboard prova que o comando rodou de verdade e quantas vezes (o nome sem ninguém online vira um
# "jogador falso" do placar).
console "scoreboard objectives add nm dummy"
console "scoreboard objectives add gt dummy"
controle /teste/entregas '[
  {"id":"mc1","nick":"Steve","comandos":["scoreboard players add Steve nm 1"]},
  {"id":"mcsay","nick":"Steve","comandos":["say nm-e2e Steve"]},
  {"id":"mcbloqueada","nick":"Steve","comandos":["scoreboard players add Steve nm 5000","op Steve"]},
  {"id":"mcvazio","nick":"Steve","comandos":["\"\" op Steve"]},
  {"id":"mcallow","nick":"Steve","comandos":["allowlist add Steve"]},
  {"id":"mcuuid","nick":"Steve","comandos":["say {uuid}"]},
  {"id":"mcteste","evento":"teste","comandos":[]},
  {"id":"mcfalha","nick":"Steve","comandos":["comandoquenaoexiste Steve"]},
  {"id":"mcoffline","nick":"Steve","comandos":["give Steve diamond 1"]},
  {"id":"mcgamertag","nick":"Some Name","edicaoJogador":"bedrock","comandos":["scoreboard players add {nick} gt 7"]},
  {"id":"mcespera","nick":"Alex","soOnline":true,"comandos":["scoreboard players add Alex nm 9000"]}
]'
espera mc1 "entregue" 60
espera mcbloqueada "falhou bloqueado" 10
espera mcvazio "falhou bloqueado" 10
espera mcallow "falhou bloqueado" 10
espera mcuuid "falhou uuid_pirata" 10
espera mcteste "entregue" 10
espera mcfalha "falhou comando_falhou" 10
espera mcoffline "falhou comando_falhou" 10
espera mcgamertag "entregue" 10
SAY=$(estado mcsay)
echo "entrega, comando proibido, {uuid}, teste do painel, comando que falha, give sem o jogador e gamertag: ok (say: $SAY)"

# 7) Confirmação perdida 2 vezes: volta pelo lease, o addon não roda de novo.
controle /teste/cortar
controle /teste/cortar
controle /teste/entregas '{"id":"mc2","nick":"Steve","comandos":["scoreboard players add Steve nm 10"]}'
espera mc2 "entregue null 2" 60
echo "confirmação perdida: ok"

# 8) Jogador offline: a entrega "só com o jogador online" espera (sem sair, sem contar tentativa) e o addon sabe dele.
espera mcespera "pendente null 0" 1
console "scriptevent nevermiss:status"
esperaLog "Esperando o jogador entrar: 1 · A confirmar: 0" 10 || falha "scriptevent nevermiss:status não mostrou o jogador esperando"
console "nevermiss status"
esperaLog "Esperando o jogador entrar: 1 · A confirmar: 0" 10 2 || falha "o comando nevermiss status (comando do addon) não respondeu no console"
console "scriptevent nevermiss:testar"
esperaLog "[NeverMiss] Conectado" 15 || falha "scriptevent nevermiss:testar não respondeu"
echo "jogador offline esperando, status e testar no console: ok"

# 9) Reinício sem repetir: roda, a confirmação não chega, o servidor reinicia; a entrega volta e o addon só confirma.
# Lease longo enquanto a confirmação não chega (senão as 5 tentativas acabam antes); vencido depois de desligar.
controle /teste/ajustar '{"cortar":1000,"leaseMs":600000}'
controle /teste/entregas '{"id":"mcreinicio","nick":"Steve","comandos":["scoreboard players add Steve nm 100"]}'
esperaLog "[NeverMiss] Rodou: scoreboard players add Steve nm 100" 30 || falha "mcreinicio não rodou"
desligar
controle /teste/ajustar '{"cortar":0,"leaseMs":5000}'
controle /teste/vencer
ligar
espera mcreinicio "entregue" 60
[ "$(quantas "[NeverMiss] Rodou: scoreboard players add Steve nm 100")" = 1 ] || falha "mcreinicio rodou de novo depois de reiniciar"
echo "reinício sem repetir: ok"

# 10) Queda (kill -9) 15 s depois de rodar, com a confirmação sem chegar. O BDS grava o mundo de tempos em tempos: o registro
# do addon pode sumir e o placar ficar (este teste achou a entrega rodando 2 vezes assim, antes do iniciar). O início fica na
# API: a entrega volta com iniciada e o addon não roda de novo. Ela fica interrompida, ou entregue se o mundo guardou o registro.
controle /teste/ajustar '{"cortar":1000,"leaseMs":600000}'
controle /teste/entregas '{"id":"mcqueda","nick":"Steve","comandos":["scoreboard players add Steve nm 1000"]}'
esperaLog "[NeverMiss] Rodou: scoreboard players add Steve nm 1000" 30 || falha "mcqueda não rodou"
sleep 15
desligar kill
controle /teste/ajustar '{"cortar":0,"leaseMs":5000}'
controle /teste/vencer
ligar
for _ in $(seq 60); do case "$(estado mcqueda)" in entregue* | falhou*) break ;; esac; sleep 1; done
QUEDA_RODOU=$(quantas "[NeverMiss] Rodou: scoreboard players add Steve nm 1000")
[ "$QUEDA_RODOU" = 1 ] || falha "mcqueda rodou $QUEDA_RODOU vezes no console (com as duas vidas do servidor)"
[ "$(inicios mcqueda)" = 1 ] || falha "o addon chamou o iniciar da mcqueda $(inicios mcqueda) vezes (a que voltou com iniciada não começa de novo)"
case "$(estado mcqueda)" in
  "entregue "*) QUEDA="o mundo guardou o registro: entregue" ;;
  "falhou interrompida "*) QUEDA="o mundo perdeu o registro: interrompida, sem rodar de novo" ;;
  *) falha "mcqueda ficou '$(estado mcqueda)', esperava entregue ou falhou interrompida" ;;
esac
console "scoreboard players list Steve"
console "scoreboard players list \"Some Name\""
sleep 3
# 1 + 10 + 100 + 1000; 111 se o mundo perdeu também o placar (a entrega fica interrompida, para o lojista reenviar). Nunca 2111.
if noLog "nm: 1111"; then
  QUEDA="$QUEDA, placar 1111"
elif noLog "nm: 111" && [ "${QUEDA#*interrompida}" != "$QUEDA" ]; then
  QUEDA="$QUEDA, placar 111 (o mundo perdeu o placar também)"
else
  falha "o placar do Steve não ficou em 1111 (nem em 111 com a entrega interrompida): uma entrega rodou 2 vezes ou não rodou"
fi
noLog "gt: 7" || falha "o gamertag com espaço não chegou como \"Some Name\" no placar"
echo "queda: $QUEDA"

# 11) O que nunca pode acontecer.
noLog "Teste do painel recebido" || falha "teste do painel não apareceu"
noLog "o {uuid} não existe no BDS" || falha "o motivo do {uuid} não apareceu no console"
vm "grep -rlF --exclude-dir=config -- $CHAVE ~/bds" && falha "a chave apareceu fora do secrets.json (log ou mundo)"
desligar
echo "BDS: $(vm "grep -m1 -oE 'Version:? [0-9.]+' ~/bds/saida.log" || echo '?') · $BETA · $PERM · say: $SAY · queda: $QUEDA"
echo "TUDO OK (BDS)"
