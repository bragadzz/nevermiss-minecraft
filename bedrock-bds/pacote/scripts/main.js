// O addon no BDS: liga o núcleo ao servidor (console, jogadores, inventário, HTTP do @minecraft/server-net) e aos comandos.
// O HTTP sai do servidor sem travar o tick (a promessa volta num tick seguinte).
import { CommandPermissionLevel, CustomCommandParamType, CustomCommandSource, CustomCommandStatus, EnchantmentType, EquipmentSlot, ItemStack, system, world } from "@minecraft/server";
import { HttpHeader, HttpRequest, HttpRequestMethod, http } from "@minecraft/server-net";
import { secrets, variables } from "@minecraft/server-admin";
import { API_PADRAO, Nucleo, validarApi } from "./nucleo.js";

const VERSAO = "1.3.1";
// O espaço do Bukkit (contrato, 17.1) → o do corpo no Bedrock. 0 a 35 são os mesmos índices no inventário do Bedrock.
const CORPO = { 36: EquipmentSlot.Feet, 37: EquipmentSlot.Legs, 38: EquipmentSlot.Chest, 39: EquipmentSlot.Head, 40: EquipmentSlot.Offhand };
// O id do Java que tem outro nome no Bedrock. O sweeping_edge não existe no Bedrock (é pulado).
const ENCANTAMENTO = { binding_curse: "binding", vanishing_curse: "vanishing" };

const log = (t) => console.log(`[NeverMiss] ${t}`);
const aviso = (t) => console.warn(`[NeverMiss] ${t}`);

let nucleo = null;
let chave;
let proxima;
let emCiclo = false;
let deNovo = false;
let pediuTeste = null;

const jogador = (nome) => {
  const n = nome.toLowerCase();
  return world.getAllPlayers().find((p) => p.name.toLowerCase() === n);
};
// Quem está no servidor, lido uma vez por tick: a volta confere até 1000 nomes esperando e não pode pedir a lista 1000 vezes.
let nomesNoTick = -1;
let nomes = new Set();
const online = (nome) => {
  if (system.currentTick !== nomesNoTick) {
    nomesNoTick = system.currentTick;
    nomes = new Set(world.getAllPlayers().map((p) => p.name.toLowerCase()));
  }
  return nomes.has(nome.toLowerCase());
};

/** O item do kit como ItemStack do Bedrock. null = não existe no Bedrock. */
function peca(item) {
  let s;
  try {
    s = new ItemStack(`minecraft:${item.item}`, 1);
  } catch {
    return null;
  }
  s.amount = Math.min(item.qtd, s.maxAmount);
  const encantavel = s.getComponent("minecraft:enchantable");
  for (const e of item.encantamentos) {
    try {
      // O Bedrock não aceita nível acima do máximo (o Bukkit aceita): fica no máximo.
      const tipo = new EnchantmentType(ENCANTAMENTO[e.id] ?? e.id);
      encantavel?.addEnchantment({ type: tipo, level: Math.min(e.nivel, tipo.maxLevel) });
    } catch {
      // encantamento que não existe no Bedrock ou não serve no item: pulado
    }
  }
  if (item.nome) s.nameTag = item.nome;
  if (item.descricao.length) {
    try {
      s.setLore(item.descricao);
    } catch {
      // descrição maior do que o Bedrock aceita: o item sai sem ela
    }
  }
  return s;
}

const guarda = {
  ler: (k) => world.getDynamicProperty(k),
  gravar: (k, v) => world.setDynamicProperty(k, v),
  apagar: (k) => world.setDynamicProperty(k, undefined),
  ids: () => world.getDynamicPropertyIds(),
};

const jogo = {
  rodar(comando) {
    // Lança erro com comando que não existe ou sintaxe errada; 0 sucessos = não fez nada (alvo offline, por exemplo).
    return world.getDimension("overworld").runCommand(comando).successCount > 0;
  },
  online,
  // Quem entra num BDS pela internet sempre passa pelo Xbox Live (o online-mode=false só vale na rede local).
  onlineMode: () => true,
  versao: () => "Bedrock Dedicated Server (Script API)",
  log,
  aviso,
  mensagem(nome, texto) {
    jogador(nome)?.sendMessage(texto);
  },
  depois(segundos, fn) {
    system.runTimeout(fn, Math.max(1, segundos) * 20);
  },
  kitExiste: (kit) => kit.itens.some((i) => peca(i) !== null),
  /** Dá o kit "vezes" vezes, item por item: no corpo, no espaço certo, no inventário e, sem lugar, no chão aos pés. */
  darKit(nome, kit, vezes) {
    const p = jogador(nome);
    if (!p) return 0;
    const inventario = p.getComponent("minecraft:inventory")?.container;
    const corpo = p.getComponent("minecraft:equippable");
    let dados = 0;
    for (let v = 0; v < vezes; v++) {
      for (const item of kit.itens) {
        const s = peca(item);
        if (!s) continue;
        try {
          const lugar = item.espaco === 40 || kit.equipar ? CORPO[item.espaco] : undefined;
          if (lugar && corpo && !corpo.getEquipment(lugar) && corpo.setEquipment(lugar, s)) {
            dados++;
            continue;
          }
          if (item.espaco < 36 && inventario && !inventario.getItem(item.espaco)) {
            inventario.setItem(item.espaco, s);
            dados++;
            continue;
          }
          const sobra = inventario ? inventario.addItem(s) : s;
          if (sobra) p.dimension.spawnItem(sobra, p.location);
          dados++;
        } catch (x) {
          aviso(`Erro ao dar um item do kit ${kit.nome}: ${x?.message ?? x}`);
        }
      }
    }
    return dados;
  },
  async post(url, corpo) {
    const req = new HttpRequest(url);
    req.method = HttpRequestMethod.Post;
    req.body = corpo;
    req.timeout = 20;
    req.headers = [
      new HttpHeader("Authorization", chave),
      new HttpHeader("User-Agent", `NeverMissMC/${VERSAO}`),
      new HttpHeader("Content-Type", "application/json; charset=utf-8"),
      new HttpHeader("Accept", "application/json"),
    ];
    const r = await http.request(req);
    let json = {};
    try {
      const j = JSON.parse(r.body);
      if (j && typeof j === "object" && !Array.isArray(j)) json = j;
    } catch {
      // corpo que não é JSON: fica {}
    }
    const retry = r.headers.find((h) => String(h.key).toLowerCase() === "retry-after")?.value;
    return { status: r.status, json, retry: typeof retry === "string" ? retry : null, erro: null };
  },
};

/**
 * A chave vem do secrets.json como SecretString: o addon só consegue pôr ela no cabeçalho, nunca ler nem escrever no log. Vai
 * inteira no Authorization (não dá para juntar o "Bearer " a um segredo); a NeverMiss aceita a chave com ou sem ele.
 */
function configurar() {
  chave = secrets.get("chave");
  const lido = variables.get("api");
  const api = validarApi(lido === undefined ? API_PADRAO : lido);
  const erro = api === null ? 'O "api" do variables.json precisa ser https.' : chave === undefined ? 'Sem chave. Crie o secrets.json com "chave": "<chave do painel>" e reinicie o servidor.' : null;
  nucleo.configurar(api, erro === null);
  if (erro !== null) {
    nucleo.situacao = erro;
    aviso(erro);
    cancelar();
    return;
  }
  if (api.startsWith("http:")) aviso(`API sem https em ${api}: use só para teste.`);
  log("Chave no secrets.json. Primeira consulta em 5 s.");
  agendar(5);
}

function cancelar() {
  if (proxima !== undefined) system.clearRun(proxima);
  proxima = undefined;
}

function agendar(segundos) {
  cancelar();
  proxima = system.runTimeout(volta, Math.max(1, segundos) * 20);
}

/** Uma volta por vez. */
async function volta() {
  proxima = undefined;
  if (emCiclo) {
    deNovo = true;
    return;
  }
  emCiclo = true;
  let segundos;
  try {
    segundos = await nucleo.ciclo();
  } catch (x) {
    aviso(`Erro na consulta à NeverMiss: ${x?.message ?? x}`);
    segundos = 60;
  }
  emCiclo = false;
  const quem = pediuTeste;
  pediuTeste = null;
  if (quem) responder(quem, nucleo.situacao);
  const adiantar = deNovo;
  deNovo = false;
  if (segundos > 0) agendar(adiantar ? 5 : segundos);
}

/** Consulta já, respeitando 5 s desde a última (a API recusa antes disso). */
function adiantar() {
  if (emCiclo) {
    deNovo = true;
    return;
  }
  agendar(Math.max(1, 5 - Math.floor((Date.now() - nucleo.ultimaConsulta) / 1000)));
}

/** quem = o jogador que pediu, ou null (o console). */
function responder(quem, texto) {
  if (quem === "console") log(texto);
  else if (quem.isValid) quem.sendMessage(`[NeverMiss] ${texto}`);
}

function nevermiss(quem, acao) {
  if (!nucleo) return responder(quem, "Ainda ligando.");
  switch ((acao || "status").toLowerCase()) {
    case "status":
      for (const linha of nucleo.status()) responder(quem, linha);
      return;
    case "testar":
      if (!nucleo.configurado()) return responder(quem, nucleo.situacao);
      pediuTeste = quem;
      responder(quem, "Consultando a NeverMiss...");
      return adiantar();
    case "recarregar":
      configurar();
      return responder(quem, `Configuração recarregada. ${nucleo.situacao}`);
    default:
      return responder(quem, "Use: nevermiss status | testar | recarregar");
  }
}

// scriptevent nevermiss:status | nevermiss:testar | nevermiss:recarregar (console ou op). Funciona em todo BDS.
system.afterEvents.scriptEventReceive.subscribe(
  (e) => {
    const quem = e.sourceType === "Entity" && e.sourceEntity?.typeId === "minecraft:player" ? e.sourceEntity : "console";
    nevermiss(quem, e.id.slice("nevermiss:".length));
  },
  { namespaces: ["nevermiss"] },
);

// /nevermiss [status|testar|recarregar] (console e op) e /kit [nome] (todo jogador), como no Java. /nmkit é o mesmo /kit,
// para o servidor em que outro addon já é dono do /kit.
system.beforeEvents.startup.subscribe(({ customCommandRegistry: registro }) => {
  const quemDe = (origem) => (origem.sourceType === CustomCommandSource.Server ? "console" : origem.sourceEntity?.typeId === "minecraft:player" ? origem.sourceEntity : null);
  registro.registerCommand(
    { name: "nevermiss:nevermiss", description: "NeverMiss: status, testar ou recarregar", permissionLevel: CommandPermissionLevel.GameDirectors, cheatsRequired: false, optionalParameters: [{ name: "acao", type: CustomCommandParamType.String }] },
    (origem, acao) => {
      const quem = quemDe(origem);
      if (!quem) return { status: CustomCommandStatus.Failure, message: "Só no console ou por um jogador." };
      system.run(() => nevermiss(quem, acao));
      return { status: CustomCommandStatus.Success };
    },
  );
  const kit = (origem, pedido) => {
    const p = quemDe(origem);
    if (!p || p === "console") return { status: CustomCommandStatus.Failure, message: "Só jogador usa o /kit." };
    if (!nucleo) return { status: CustomCommandStatus.Failure, message: "Ainda ligando." };
    system.run(async () => {
      try {
        const r = await nucleo.kits.comando(p.name, pedido);
        if (r && p.isValid) p.sendMessage(r);
      } catch (x) {
        aviso(`Erro no /kit: ${x?.message ?? x}`);
      }
    });
    return { status: CustomCommandStatus.Success };
  };
  for (const name of ["nevermiss:kit", "nevermiss:nmkit"]) {
    registro.registerCommand({ name, description: "Kits da NeverMiss", permissionLevel: CommandPermissionLevel.Any, cheatsRequired: false, optionalParameters: [{ name: "nome", type: CustomCommandParamType.String }] }, kit);
  }
});

world.afterEvents.worldLoad.subscribe(() => {
  try {
    nucleo = new Nucleo(jogo, guarda, VERSAO);
  } catch (x) {
    // Registro que não abre: parar. Seguir com ele vazio faria rodar de novo o que já rodou.
    aviso(`Não consegui ler o registro do mundo (${x?.message ?? x}). Nada vai ser entregue.`);
    return;
  }
  log(`NeverMiss ${VERSAO} ligado (bds).`);
  configurar();
  for (const p of world.getAllPlayers()) nucleo.kits.aoEntrar(p.name);
});

world.afterEvents.playerSpawn.subscribe(({ player, initialSpawn }) => {
  if (!initialSpawn || !nucleo || !nucleo.configurado()) return;
  nucleo.kits.aoEntrar(player.name);
  if (nucleo.aoEntrar(player.name)) adiantar();
});

world.afterEvents.playerLeave.subscribe(({ playerName }) => nucleo?.kits.aoSair(playerName));
