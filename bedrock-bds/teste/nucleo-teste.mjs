// O laço inteiro contra a API falsa (teste/api-falsa.mjs, porta 4011), sem servidor: como o NucleoTest.java e o
// nucleo-teste.php. Uso: node teste/nucleo-teste.mjs  (sobe a API falsa sozinho). Termina com "TUDO OK".
import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Nucleo, bloqueado, nomeNaApi, tempo, validarApi } from "../pacote/scripts/nucleo.js";

const API = "http://127.0.0.1:4011";
const CHAVE = `nmmc_${"e".repeat(43)}`;
const api = spawn(process.execPath, [join(dirname(fileURLToPath(import.meta.url)), "api-falsa.mjs"), "4011", CHAVE], { stdio: "ignore" });
process.on("exit", () => api.kill());

function ok(condicao, mensagem) {
  if (!condicao) {
    console.error(`FALHOU: ${mensagem}`);
    process.exit(1);
  }
}
const igual = (a, b) => JSON.stringify(a) === JSON.stringify(b);

let chave = CHAVE;
class JogoFalso {
  jogadores = new Set();
  rodados = [];
  falham = [];
  logs = [];
  mensagens = [];
  kitsDados = [];
  naoExistem = new Set();
  rodar(c) {
    this.rodados.push(c);
    if (c.startsWith("lanca")) throw new Error("sintaxe");
    return !this.falham.includes(c.split(" ")[0]);
  }
  online(n) {
    return this.jogadores.has(n.toLowerCase());
  }
  onlineMode() {
    return true;
  }
  versao() {
    return "Bedrock Dedicated Server (Script API)";
  }
  log(t) {
    this.logs.push(t);
  }
  aviso(t) {
    this.logs.push(`AVISO ${t}`);
  }
  mensagem(nome, texto) {
    this.mensagens.push(`${nome}: ${texto}`);
  }
  depois() {}
  kitExiste(kit) {
    return kit.itens.some((i) => !this.naoExistem.has(i.item));
  }
  darKit(nome, kit, vezes) {
    const n = kit.itens.filter((i) => !this.naoExistem.has(i.item)).length * vezes;
    this.kitsDados.push(`${nome} ${kit.chave} x${vezes}`);
    return n;
  }
  async post(url, corpo) {
    const r = await fetch(url, { method: "POST", body: corpo, redirect: "error", headers: { Authorization: `Bearer ${chave}`, "User-Agent": "NeverMissMC/1.2.0", "Content-Type": "application/json; charset=utf-8" } });
    let json = {};
    try {
      json = JSON.parse(await r.text());
    } catch {}
    return { status: r.status, json, retry: r.headers.get("retry-after"), erro: null };
  }
}

/** As dynamic properties do mundo. */
const guardaNova = (inicial = {}) => {
  const m = new Map(Object.entries(inicial));
  return { m, ler: (k) => m.get(k), gravar: (k, v) => m.set(k, v), apagar: (k) => m.delete(k), ids: () => [...m.keys()] };
};

async function controle(caminho, corpo = {}) {
  const r = await fetch(API + caminho, { method: "POST", body: JSON.stringify(corpo) });
  ok(r.ok, `API falsa respondeu ${r.status} em ${caminho}`);
  return r.json();
}
const estado = async (id) => (await controle("/teste/estado")).entregas[id];
const entrega = (id, nick, comandos, extra = {}) => controle("/teste/entregas", { id, nick, comandos, ...extra });

async function plugin(jogo, guarda = guardaNova(), limpar = true) {
  if (limpar) await controle("/teste/limpar");
  const n = new Nucleo(jogo, guarda, "1.2.0");
  n.configurar(API, true);
  return n;
}

for (let i = 0; i < 50; i++) {
  try {
    await controle("/teste/estado");
    break;
  } catch {
    await new Promise((r) => setTimeout(r, 100));
  }
}

// 1) Entrega, o corpo do pull (plataforma bds, edição bedrock, sem Floodgate, o kit em recursos) e o User-Agent.
let jogo = new JogoFalso();
let n = await plugin(jogo);
await entrega("mc1", "Steve", ["say oi Steve", "/give Steve diamond 2"]);
let s = await n.ciclo();
ok(s === 30, `próxima consulta em ${s} s, esperava 30`);
ok(igual(jogo.rodados, ["say oi Steve", "give Steve diamond 2"]), `comandos rodados: ${JSON.stringify(jogo.rodados)}`);
let e = await estado("mc1");
ok(e.estado === "entregue" && igual(e.resultado, [true, true]), `mc1: ${JSON.stringify(e)}`);
let a = await controle("/teste/estado");
ok(a.ultimoPull.versao === "1.2.0" && igual(a.ultimoPull.recursos, ["kit"]), `versão e recursos no pull: ${JSON.stringify(a.ultimoPull)}`);
ok(igual(a.ultimoPull.servidor, { versao: "Bedrock Dedicated Server (Script API)", onlineMode: true, plataforma: "bds", edicao: "bedrock", bedrock: null }), `servidor no pull: ${JSON.stringify(a.ultimoPull.servidor)}`);
ok(igual(a.agentes, ["NeverMissMC/1.2.0"]), `User-Agent: ${JSON.stringify(a.agentes)}`);
ok(n.situacao === "Conectado" && n.aConfirmar() === 0, `situação: ${n.situacao}`);
console.log("entrega: ok");

// 2) Teste do painel: nada roda.
jogo = new JogoFalso();
n = await plugin(jogo);
await entrega("mcteste", null, [], { evento: "teste" });
await n.ciclo();
ok(jogo.rodados.length === 0 && jogo.logs.includes("Teste do painel recebido"), "teste do painel");
ok((await estado("mcteste")).estado === "entregue", "teste do painel não ficou entregue");
console.log("teste do painel: ok");

// 3) Comando proibido: a entrega falha inteira e nada roda.
for (const c of [
  "op Steve", "/op Steve", "OP Steve", "minecraft:op Steve", "deop Steve", "stop", "eban Steve", "esudo Steve x", "whitelist off",
  "allowlist add Steve", "allowlist off", "ban-ip 1.2.3.4", '"op" Steve', "'op' Steve", '"" op Steve', "/ op Steve", "op Steve",
  "lp user Steve permission set *", 'setuperm Steve "*"', "lp user Steve permission set essentials.*", "say a\nop Steve", "say \x07",
  "", "   ", "/", "a".repeat(257), "end", "shutdown", "greload", "velocity reload", "reload all",
  "//op Steve", "/ /op Steve", "op@a", "OP@a", "minecraft:op@s", '"/op" Steve', "execute@a ~ ~ ~ say x",
]) {
  ok(bloqueado(c), `devia bloquear: ${JSON.stringify(c)}`);
}
for (const c of ["say oi", "give Steve diamond 1", "kit vip Steve", "e Steve", "economy give Steve 10", 'tell Steve "op"', "say opa", "say a.b", "a".repeat(256), "scoreboard players add Steve vip 30"]) {
  ok(!bloqueado(c), `não devia bloquear: ${JSON.stringify(c)}`);
}
ok(bloqueado(null), "comando null devia bloquear");
jogo = new JogoFalso();
n = await plugin(jogo);
await entrega("mcbloq", "Steve", ["say isto nao pode rodar", '"" op Steve']);
await n.ciclo();
e = await estado("mcbloq");
ok(jogo.rodados.length === 0 && e.estado === "falhou" && e.motivo === "bloqueado", `mcbloq: ${JSON.stringify(e)} ${JSON.stringify(jogo.rodados)}`);
console.log("comando proibido: ok");

// 4) Nick: Java com o regex de sempre, Bedrock com o do gamertag (sem o #1234). O nome no BDS leva o espaço, entre aspas.
jogo = new JogoFalso();
n = await plugin(jogo);
await entrega("mcnick1", "a b", ["say x"]);
await entrega("mcnick2", "Steve\n", ["say x"]);
await entrega("mcnick3", "1Steve", ["say x"], { edicaoJogador: "bedrock" });
await entrega("mcnick4", "Cool Guy#1234", ["say {nick}"], { edicaoJogador: "bedrock" });
await entrega("mcgt", "Some Name", ["give Some_Name diamond 1", "say {nick} comprou", "tell Some_Names oi", 'kit "Some_Name" vip', 'tell "{nick}" oi'], { edicaoJogador: "bedrock" });
await entrega("mcgt2", "Alex", ["give {nick} diamond 1"], { edicaoJogador: "bedrock" });
await n.ciclo();
for (const id of ["mcnick1", "mcnick2", "mcnick3", "mcnick4"]) {
  e = await estado(id);
  ok(e.estado === "falhou" && e.motivo === "bloqueado", `${id}: ${JSON.stringify(e)}`);
}
ok(igual(jogo.rodados, ['give "Some Name" diamond 1', 'say "Some Name" comprou', "tell Some_Names oi", 'kit "Some Name" vip', 'tell "Some Name" oi', "give Alex diamond 1"]), `gamertag: ${JSON.stringify(jogo.rodados)}`);
ok((await estado("mcgt")).estado === "entregue" && (await estado("mcgt2")).estado === "entregue", "gamertag não ficou entregue");
ok(nomeNaApi("Some Name") === "Some_Name" && nomeNaApi("Steve") === "Steve" && nomeNaApi("a#1") === null && nomeNaApi("Bo") === null, "nome do /kit na API");
console.log("nick e gamertag: ok");

// 5) Só com o jogador online: Alex fora não recebe; quando entra, o pull leva o nick e a entrega roda.
jogo = new JogoFalso();
n = await plugin(jogo);
await entrega("mcalex", "Alex", ["give Alex diamond 1"], { soOnline: true });
await entrega("mcgtalex", "Big Alex", ["give {nick} diamond 1"], { soOnline: true, edicaoJogador: "bedrock" });
await n.ciclo();
ok(jogo.rodados.length === 0, "rodou com o jogador fora");
e = await estado("mcalex");
ok(e.estado === "pendente" && e.tentativas === 0, `mcalex devia esperar: ${JSON.stringify(e)}`);
ok(n.status()[3].includes("Esperando o jogador entrar: 2"), `status: ${n.status()[3]}`);
ok(!n.aoEntrar("Steve") && n.aoEntrar("alex") && n.aoEntrar("BIG ALEX"), "aoEntrar");
jogo.jogadores.add("alex").add("big alex");
await n.ciclo();
ok(igual((await controle("/teste/estado")).ultimoPull.online, ["Alex", "Big Alex"]), "online no pull");
ok(igual(jogo.rodados, ["give Alex diamond 1", 'give "Big Alex" diamond 1']), `só online: ${JSON.stringify(jogo.rodados)}`);
ok((await estado("mcalex")).estado === "entregue" && (await estado("mcgtalex")).estado === "entregue", "só online não ficou entregue");
// Saiu antes de rodar: adiada (volta para a fila sem contar tentativa).
let r = n.executar({ id: "mcbob", nick: "Bob", soOnline: true, teste: false, bedrock: false, comandos: ["give Bob diamond 1"], kit: null });
ok(r.estado === "adiada" && r.comandos === null && jogo.rodados.length === 2, `adiada: ${JSON.stringify(r)}`);
// Até 1000 nicks esperando: o que passa disso não vai.
jogo = new JogoFalso();
n = await plugin(jogo);
await controle("/teste/ajustar", { esperandoExtra: 1500 });
await n.ciclo();
ok(n.status()[3].includes("Esperando o jogador entrar: 1000"), `esperando acima de 1000: ${n.status()[3]}`);
for (let i = 0; i < 1500; i++) jogo.jogadores.add(`espera${i}`);
await n.ciclo();
ok((await controle("/teste/estado")).ultimoPull.online.length === 1000, "mandou mais de 1000 online");
console.log("só online: ok");

// 6) Confirmação perdida 2 vezes: a entrega volta (lease vencido) e o plugin só confirma, sem rodar de novo.
jogo = new JogoFalso();
n = await plugin(jogo);
await controle("/teste/ajustar", { leaseMs: 0 });
await controle("/teste/cortar");
await controle("/teste/cortar");
await entrega("mc2", "Steve", ["give Steve diamond 1"]);
await n.ciclo();
ok(n.aConfirmar() === 1 && (await estado("mc2")).estado === "entregando", "a confirmação cortada não ficou guardada");
await n.ciclo();
e = await estado("mc2");
ok(e.estado === "entregue" && e.tentativas === 2 && igual(jogo.rodados, ["give Steve diamond 1"]) && n.aConfirmar() === 0, `mc2: ${JSON.stringify(e)} ${JSON.stringify(jogo.rodados)}`);
console.log("confirmação perdida: ok");

// 7) O registro no mundo: um núcleo novo (servidor reiniciado) lê o que já rodou e não roda de novo; o "rodando" sem
// "feita" vira interrompida; o que passou de 30 dias e o que está cortado saem.
const velho = Date.now() - 31 * 24 * 60 * 60 * 1000;
const inicial = { "nm:mcinterrompida": `rodando ${Date.now()}`, "nm:mcvelha": `rodando ${velho}`, "nm:mccortada": "rodan", "outro:addon": "fica" };
for (let i = 0; i < 60; i++) inicial[`nm:mclote${i}`] = `rodando ${Date.now()}`;
const guarda = guardaNova(inicial);
jogo = new JogoFalso();
await controle("/teste/limpar");
await controle("/teste/entregas", Array.from({ length: 60 }, (_, i) => ({ id: `mclote${i}`, nick: "Steve", comandos: ["say lote"] })));
await entrega("mcinterrompida", "Steve", ["say nao pode rodar"]);
n = await plugin(jogo, guarda, false);
ok(n.aConfirmar() === 61, `interrompidas na fila: ${n.aConfirmar()}`);
await controle("/teste/ajustar", { leaseMs: 0 });
await n.ciclo();
ok(jogo.rodados.length === 0, `rodou uma interrompida: ${JSON.stringify(jogo.rodados)}`);
e = await estado("mcinterrompida");
ok(e.estado === "falhou" && e.motivo === "interrompida", `mcinterrompida: ${JSON.stringify(e)}`);
ok((await estado("mclote59")).motivo === "interrompida" && (await controle("/teste/estado")).confirmacoes === 2, "lote de 50 em 50");
ok(!guarda.m.has("nm:mcvelha") && !guarda.m.has("nm:mccortada") && guarda.m.get("outro:addon") === "fica", "o registro velho ou cortado ficou (ou apagou o de outro addon)");
await entrega("mc3", "Steve", ["give Steve diamond 3"]);
await (await plugin(jogo, guarda, false)).ciclo();
await controle("/teste/limpar");
await entrega("mc3", "Steve", ["give Steve diamond 3"]);
n = await plugin(jogo, guarda, false);
ok(n.aConfirmar() === 0, "interrompida voltou depois de confirmada");
await n.ciclo();
ok(igual(jogo.rodados, ["give Steve diamond 3"]) && (await estado("mc3")).estado === "entregue", `mc3 depois de reiniciar: ${JSON.stringify(jogo.rodados)}`);
// O mundo não grava: nada roda.
jogo = new JogoFalso();
n = await plugin(jogo, { ...guardaNova(), gravar: () => { throw new Error("cheio"); } });
await entrega("mcsemdisco", "Steve", ["say x"]);
await n.ciclo();
ok(jogo.rodados.length === 0 && (await estado("mcsemdisco")).motivo === "erro", "rodou sem conseguir gravar o registro");
console.log("registro e interrompida: ok");

// 8) Comando que falha (0 sucessos ou erro de sintaxe) e {uuid}: o BDS não dá o UUID, nem com o jogador online.
jogo = new JogoFalso();
jogo.falham = ["comandoquenaoexiste"];
jogo.jogadores.add("steve");
n = await plugin(jogo);
await entrega("mcfalha", "Steve", ["say antes", "comandoquenaoexiste Steve", "lanca erro"]);
await entrega("mcuuid", "Steve", ["lp user {uuid} parent add vip"]);
await n.ciclo();
e = await estado("mcfalha");
ok(e.estado === "falhou" && e.motivo === "comando_falhou" && igual(e.resultado, [true, false, false]), `mcfalha: ${JSON.stringify(e)}`);
e = await estado("mcuuid");
ok(e.estado === "falhou" && e.motivo === "uuid_pirata" && !jogo.rodados.some((c) => c.includes("uuid")), `mcuuid: ${JSON.stringify(e)}`);
ok(jogo.logs.some((l) => l.includes("o {uuid} não existe no BDS")), "o motivo do {uuid} não apareceu no console");
console.log("comando que falha e uuid: ok");

// 9) Kit (contrato, 17.3): dado na compra com o jogador online, o resgate marcado, kit_incompativel e só o resgate.
const kit = (id, itens) => ({ id, nome: "VIP Diamante", chave: "vip-diamante", equipar: true, itens });
const espada = { espaco: 0, item: "diamond_sword", qtd: 1, encantamentos: [{ id: "sharpness", nivel: 5 }], nome: "&bLâmina", descricao: "Só VIP\nNão perca" };
const capacete = { espaco: 39, item: "diamond_helmet", qtd: 1, encantamentos: [], nome: null, descricao: null };
jogo = new JogoFalso();
jogo.jogadores.add("steve").add("cool guy");
let gk = guardaNova();
n = await plugin(jogo, gk);
await entrega("mckit", "Steve", ["say kit"], { soOnline: true, acoes: [{ tipo: "kit", darAgora: true, vezes: 2, kit: kit("6721", [espada, capacete]), resgate: { intervaloS: 300, ate: null } }] });
await entrega("mckitgt", "Cool Guy", [], { soOnline: true, edicaoJogador: "bedrock", acoes: [{ tipo: "kit", darAgora: true, vezes: 1, kit: kit("6721", [capacete]), resgate: null }] });
await entrega("mckitnada", "Steve", ["say nao pode"], { soOnline: true, acoes: [{ tipo: "kit", darAgora: true, vezes: 1, kit: kit("6722", [{ ...espada, item: "netherite_sword" }]), resgate: null }] });
await entrega("mcsoresgate", "Alex", [], { acoes: [{ tipo: "kit", darAgora: false, vezes: 1, kit: kit("6721", [espada]), resgate: { intervaloS: 60, ate: null } }] });
await entrega("mckitruim", "Steve", [], { soOnline: true, acoes: [{ tipo: "kit", darAgora: true, vezes: 1, kit: { id: "x y" }, resgate: null }] });
// Ação que o addon não conhece, ou um segundo kit: falha sem rodar nada (como o acaoDesconhecidaFalhaSemRodar do Java).
await entrega("mcacao", "Steve", ["say nao pode"], { acoes: [{ tipo: "pet" }] });
await entrega("mcacao2", "Steve", [], { acoes: [{ tipo: "kit", darAgora: false, vezes: 1, kit: kit("6721", [espada]), resgate: null }, { tipo: "pet" }] });
jogo.naoExistem.add("netherite_sword");
// A primeira volta só fica sabendo de quem espera (e volta em 5 s, porque eles estão online); a segunda entrega.
ok((await n.ciclo()) === 5 && jogo.kitsDados.length === 0, "a volta com o jogador esperando online não adiantou");
await n.ciclo();
ok(igual(jogo.kitsDados, ["Steve vip-diamante x2", "Cool Guy vip-diamante x1"]), `kits dados: ${JSON.stringify(jogo.kitsDados)}`);
ok(igual(jogo.rodados, ["say kit"]), `comandos do kit: ${JSON.stringify(jogo.rodados)}`);
ok((await estado("mckit")).estado === "entregue" && (await estado("mckitgt")).estado === "entregue" && (await estado("mcsoresgate")).estado === "entregue", "kit não ficou entregue");
e = await estado("mckitnada");
ok(e.estado === "falhou" && e.motivo === "kit_incompativel", `mckitnada: ${JSON.stringify(e)}`);
ok((await estado("mckitruim")).motivo === "erro", "kit fora do formato");
ok((await estado("mcacao")).motivo === "erro" && (await estado("mcacao2")).motivo === "erro", "ação desconhecida não falhou");
ok(Number(gk.m.get("nmr:mckit")) > 0 && !gk.m.has("nmr:mcsoresgate"), "o kit dado na compra não contou como o primeiro resgate");
ok(jogo.mensagens.includes("Steve: §aVocê recebeu o kit VIP Diamante. Use /kit vip-diamante a cada 5 min."), `mensagem: ${JSON.stringify(jogo.mensagens)}`);
r = n.executar({ id: "mckitfora", nick: "Bob", soOnline: false, teste: false, bedrock: false, comandos: [], kit: { darAgora: true, vezes: 1, kit: kit("6721", [espada]), resgate: null } });
ok(r.estado === "adiada", `kit com o jogador fora: ${JSON.stringify(r)}`);
r = n.executar({ id: "mckitsemnick", nick: null, soOnline: false, teste: false, bedrock: false, comandos: [], kit: { darAgora: true, vezes: 1, kit: kit("6721", [espada]), resgate: null } });
ok(r.motivo === "kit_incompativel", `kit sem nick: ${JSON.stringify(r)}`);
// Nenhum item entrou: kit_incompativel, sem a mensagem de "recebeu".
const darKit = jogo.darKit;
jogo.darKit = () => 0;
jogo.mensagens = [];
r = n.executar({ id: "mckitzero", nick: "Steve", soOnline: false, teste: false, bedrock: false, comandos: [], kit: { darAgora: true, vezes: 1, kit: kit("6721", [espada]), resgate: null } });
ok(r.motivo === "kit_incompativel" && jogo.mensagens.length === 0, `kit sem nenhum item dado: ${JSON.stringify(r)} ${JSON.stringify(jogo.mensagens)}`);
jogo.darKit = darKit;
// O mundo não grava: nem o resgate nem o kit saem.
const gr = guardaNova();
const nr = new Nucleo(jogo, { ...gr, gravar: () => { throw new Error("cheio"); } }, "1.2.0");
jogo.kitsDados = [];
r = nr.executar({ id: "mckitdisco", nick: "Steve", soOnline: false, teste: false, bedrock: false, comandos: ["say x"], kit: { darAgora: true, vezes: 1, kit: kit("6721", [espada]), resgate: { intervaloS: 60 } } });
ok(r.motivo === "erro" && jogo.kitsDados.length === 0 && !jogo.rodados.includes("say x"), `kit sem conseguir gravar: ${JSON.stringify(r)}`);
console.log("kit na compra: ok");

// 10) /kit (contrato, 17.4): a lista, o resgate com intervalo e o nome do jogador Bedrock com "_" na API.
await controle("/teste/ajustar", {
  kits: {
    kits: { 6721: kit("6721", [espada, capacete]) },
    direitos: [
      { id: "mckit", nick: "steve", kit: "6721", intervaloS: 300, ate: null },
      { id: "mcoutro", nick: "Steve", kit: "6721", intervaloS: 60, ate: null },
      { id: "mcvencido", nick: "Steve", kit: "6721", intervaloS: 1, ate: "2020-01-01T00:00:00.000Z" },
      { id: "mcgtkit", nick: "Cool_Guy", kit: "6721", intervaloS: 60, ate: null },
    ],
    pausado: false,
  },
});
jogo.kitsDados = [];
ok((await n.kits.comando("Steve", null)) === "§aSeus kits: vip-diamante (agora). Use /kit <nome>.", "lista do /kit");
ok((await n.kits.comando("Steve", "VIP-Diamante")) === "§aVocê pegou o kit VIP Diamante.", "/kit com o direito livre");
ok((await n.kits.comando("Steve", "vip-diamante")).startsWith("§eEspere 1 min"), "/kit seguido deu dois kits");
ok((await n.kits.comando("Steve", "outro")) === "§cVocê não tem esse kit. Use /kit para ver os seus.", "kit que não é dele");
ok((await n.kits.comando("Cool Guy", "vip-diamante")) === "§aVocê pegou o kit VIP Diamante.", "/kit do gamertag com espaço");
ok(igual(jogo.kitsDados, ["Steve vip-diamante x1", "Cool Guy vip-diamante x1"]) && Number(gk.m.get("nmr:mcoutro")) > 0, `resgates: ${JSON.stringify(jogo.kitsDados)}`);
ok(igual((await controle("/teste/estado")).kitsPedidos.slice(-2), [["Steve"], ["Cool_Guy"]]), "nicks pedidos ao /kits");
ok(tempo(200000) === "3 min 20 s" && tempo(90061000) === "1 d 1 h" && tempo(3600000) === "1 h", "tempo");
console.log("/kit: ok");

// 11) Erros da API e a rede fora.
jogo = new JogoFalso();
n = await plugin(jogo);
chave = `nmmc_${"x".repeat(43)}`;
ok((await n.ciclo()) === 300 && n.situacao.includes("Chave inválida") && n.situacao.includes("Bearer"), `401: ${n.situacao}`);
chave = CHAVE;
await controle("/teste/ajustar", { adicional: false });
ok((await n.ciclo()) === 300 && n.situacao.includes("adicional"), `403: ${n.situacao}`);
await controle("/teste/ajustar", { adicional: true, minima: "9.0.0" });
ok((await n.ciclo()) === 600 && n.situacao.includes("mínima 9.0.0"), `426: ${n.situacao}`);
await controle("/teste/ajustar", { minima: "1.0.0", intervaloMs: 60000, ultimaConsulta: Date.now() });
s = await n.ciclo();
ok(s >= 55 && s <= 60, `429 com Retry-After: ${s}`);
await controle("/teste/ajustar", { intervaloMs: 0, pausado: true });
await entrega("mcpausa", "Steve", ["say pausa"]);
await n.ciclo();
ok(n.pausado && n.situacao === "Entregas pausadas pela NeverMiss" && jogo.rodados.length === 0 && (await estado("mcpausa")).estado === "pendente", "pausado");
n.configurar("http://127.0.0.1:1", true);
const esperas = [];
for (let i = 0; i < 5; i++) esperas.push(await n.ciclo());
ok(igual(esperas, [30, 60, 120, 240, 300]) && n.situacao.startsWith("Sem resposta da NeverMiss"), `rede fora: ${esperas} ${n.situacao}`);
n.configurar(API, false);
ok((await n.ciclo()) === -1, "sem chave o laço para");
console.log("erros: ok");

// 12) Config: só https (http só em localhost). A chave nem passa pelo núcleo: o status e o log não têm como mostrar.
ok(validarApi("https://api-website.nevermissapps.com/") === "https://api-website.nevermissapps.com", "https");
ok(validarApi("http://127.0.0.1:4011") === "http://127.0.0.1:4011" && validarApi("http://localhost:1") !== null, "http local");
for (const u of ["http://exemplo.com", "ftp://127.0.0.1", "127.0.0.1:4011", "", "http://127.0.0.1.exemplo.com", "http://localhost@exemplo.com", "http://localhost:80@exemplo.com/", "http://host.lima.internal:4011", null, 5]) {
  ok(validarApi(u) === null, `devia recusar ${u}`);
}
n = await plugin(new JogoFalso());
ok(n.status()[1] === "Chave: no secrets.json · API: http://127.0.0.1:4011", `status: ${n.status()[1]}`);
console.log("config: ok");

console.log("TUDO OK");
process.exit(0);
