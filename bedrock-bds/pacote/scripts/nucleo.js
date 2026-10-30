// O laço de entrega, o mesmo do plugin Java e do PocketMine: pergunta à API se há entregas, roda os comandos no console e
// confirma. Não conhece o Minecraft (ver o "jogo" do main.js), para dar para testar no Node sem servidor.
// A chave nunca passa por aqui: o main.js põe o cabeçalho com o SecretString do secrets.json.

export const API_PADRAO = "https://api-website.nevermissapps.com";
export const NICK = /^[A-Za-z0-9_]{3,16}$/;
// O gamertag do Xbox como aparece no Minecraft: 1 a 16 letras, números e espaço, começa com letra, sem espaço duplo nem nas
// pontas, sem o "#1234" do gamertag novo (a mesma régua da API, do bot, da vitrine e do painel).
export const GAMERTAG = /^(?=.{1,16}$)[A-Za-z][A-Za-z0-9]*(?: [A-Za-z0-9]+)*$/;
const ID = /^[A-Za-z0-9_-]{1,64}$/;
// A mesma lista da API; nem o painel nem a API desligam esta checagem. O EssentialsX aceita cada um com "e" na frente.
// "allowlist" é o "whitelist" do BDS (só aqui por enquanto).
const PROIBIDOS = new Set([
  "op", "deop", "stop", "restart", "reload", "rl", "ban", "ban-ip", "banip", "ipban", "tempban", "tempbanip", "tempipban",
  "pardon", "pardon-ip", "pardonip", "unban", "unbanip", "whitelist", "execute", "sudo", "plugman", "end", "shutdown",
  "greload", "velocity", "allowlist",
]);
const MAX_COMANDOS = 100;
// Quantos nicks esperando o jogador entrar a API manda (e o plugin devolve os que estão online).
const MAX_ESPERANDO = 1000;
const MAX_LOTE = 50;
const GUARDAR_MS = 30 * 24 * 60 * 60 * 1000;

export const agora = () => Date.now();

/** Só https; http só em localhost, para teste. null = não serve. */
export function validarApi(texto) {
  if (typeof texto !== "string") return null;
  const limpo = texto.trim().replace(/\/+$/, "");
  const m = /^([a-z][a-z0-9+.-]*):\/\/([^/:?#]+)(?::\d{1,5})?(?:[/?#].*)?$/i.exec(limpo);
  if (!m) return null;
  const esquema = m[1].toLowerCase();
  const host = m[2].toLowerCase();
  return esquema === "https" || (esquema === "http" && (host === "localhost" || host === "127.0.0.1")) ? limpo : null;
}

// Todas as barras e espaços do começo: o que roda é o mesmo texto que a lista proibida confere ("//op" e "/ /op" viram "op").
const semBarra = (c) => c.trim().replace(/^[\s/]+/u, "");

// Os argumentos como um console que entende aspas lê a linha: o que está entre aspas é um argumento só, e o "" some.
function argumentos(linha) {
  const args = [];
  for (const m of linha.matchAll(/"((?:\\.|[^\\"])*)"|(\S+)/gu)) {
    const a = m[1] ? m[1] : m[2] ?? "";
    if (a !== "") args.push(a.replace(/\\([\\"])/gu, "$1"));
  }
  return args;
}

export function bloqueado(comando) {
  if (typeof comando !== "string" || semBarra(comando) === "" || [...comando].length > 256 || /\p{Cc}/u.test(comando)) return true;
  const texto = semBarra(comando).toLowerCase();
  // Duas leituras: a da API (palavras separadas por espaço, sem as aspas das pontas) e a de um console que entende aspas.
  const palavras = texto.split(/[\s\p{Z}]+/u).map((p) => p.replace(/^["']+|["']+$/g, ""));
  const doConsole = argumentos(texto);
  for (const p of [palavras[0] ?? "", doConsole[0] ?? ""]) {
    // O nome do comando vai até o primeiro caractere que não é de nome: o Bedrock pode ler "op@a" como "op @a".
    const nome = p.replace(/^["'/]+/u, "").replace(/[^a-z0-9_:-].*$/su, "");
    const primeira = nome.slice(nome.lastIndexOf(":") + 1);
    if (PROIBIDOS.has(primeira) || (primeira.startsWith("e") && PROIBIDOS.has(primeira.slice(1)))) return true;
  }
  return [...palavras, ...doConsole].some((p) => p === "*" || p.endsWith(".*"));
}

// O {nick} vira o nome do jogador, entre aspas quando tem espaço (o console do Bedrock lê "Nome Com Espaco" como um nome).
// A API troca o espaço do gamertag por "_" (o nome do Floodgate no Java); no BDS o nome tem o espaço, então a palavra
// "Nome_Com_Espaco" volta a ser "Nome Com Espaco", entre aspas. Quem já pôs aspas ("{nick}") não ganha aspas dobradas.
function comNome(comando, nome) {
  if (!nome.includes(" ")) return comando.split("{nick}").join(nome);
  const certo = `"${nome}"`;
  const daApi = nome.replace(/ /g, "_");
  return comando
    .split('"{nick}"').join(certo)
    .split("{nick}").join(certo)
    .split(" ")
    .map((p) => (p === daApi || p === `"${daApi}"` ? certo : p))
    .join(" ");
}

/**
 * O nome que vai para a rota do /kit (contrato, 17.4), que só aceita o formato do nick: o gamertag com "_" no lugar do espaço.
 * O que não vira nick (gamertag de 1 ou 2 letras) fica sem /kit, senão a rota recusaria o lote inteiro.
 */
export function nomeNaApi(nome) {
  if (typeof nome !== "string" || !(NICK.test(nome) || GAMERTAG.test(nome))) return null;
  const n = nome.replace(/ /g, "_");
  return NICK.test(n) ? n : null;
}

/** 200 s → "3 min 20 s"; o que sobra de um dia em horas; de uma hora, em minutos. */
export function tempo(ms) {
  const s = Math.ceil(ms / 1000);
  if (s >= 86400) return `${Math.floor(s / 86400)} d` + (s % 86400 >= 3600 ? ` ${Math.floor((s % 86400) / 3600)} h` : "");
  if (s >= 3600) return `${Math.floor(s / 3600)} h` + (s % 3600 >= 60 ? ` ${Math.floor((s % 3600) / 60)} min` : "");
  if (s >= 60) return `${Math.floor(s / 60)} min` + (s % 60 > 0 ? ` ${s % 60} s` : "");
  return `${s} s`;
}

const texto = (v) => (typeof v === "string" ? v : typeof v === "number" && Number.isFinite(v) ? String(v) : null);
const inteiro = (v, padrao) => (Number.isInteger(v) ? v : typeof v === "string" && /^\s*-?\d{1,9}\s*$/.test(v) ? parseInt(v, 10) : padrao);
const entre = (v, min, max) => Math.max(min, Math.min(max, v));
const limpo = (t, max) => {
  if (typeof t !== "string") return null;
  const l = [...t.replace(/\p{Cc}/gu, "").trim()].slice(0, max).join("");
  return l === "" ? null : l;
};
// O & das cores vira §, como o translateAlternateColorCodes do Bukkit.
const cor = (t) => t.replace(/&([0-9a-fk-orx])/gi, (_, c) => `§${c.toLowerCase()}`);

const entregue = (id, comandos) => ({ id, estado: "entregue", motivo: null, comandos });
const falhou = (id, motivo, comandos = null) => ({ id, estado: "falhou", motivo, comandos });
// O jogador não está online: volta para a fila sem contar como falha.
const adiada = (id) => ({ id, estado: "adiada", motivo: null, comandos: null });

/**
 * Os ids que já rodaram neste mundo, nas dynamic properties ("nm:<id>"): é o que garante que uma entrega nunca roda 2 vezes,
 * mesmo que a confirmação se perca. Mesmo formato do executadas.txt: "rodando <ms>" e "feita <ms> <estado> <motivo|-> <bits|->".
 * guarda = { ler(chave), gravar(chave, valor), apagar(chave), ids() }.
 */
export class Registro {
  constructor(guarda) {
    this.guarda = guarda;
    this.rodando = new Set();
    this.feitas = new Map();
    const limite = agora() - GUARDAR_MS;
    for (const k of guarda.ids()) {
      if (!k.startsWith("nm:")) continue;
      const id = k.slice(3);
      const p = String(guarda.ler(k) ?? "").split(" ");
      const ms = /^\d{1,15}$/.test(p[1] ?? "") ? Number(p[1]) : 0;
      if (ms < limite || !(p[0] === "rodando" || (p[0] === "feita" && p.length >= 5))) {
        guarda.apagar(k);
        continue;
      }
      if (p[0] === "rodando") this.rodando.add(id);
      else this.feitas.set(id, { id, estado: p[2], motivo: p[3] === "-" ? null : p[3], comandos: p[4] === "-" ? [] : [...p[4]].map((b) => b === "1") });
    }
  }

  feita(id) {
    return this.feitas.get(id) ?? null;
  }

  /** Começou a rodar e não terminou: o servidor caiu no meio. */
  interrompida(id) {
    return this.rodando.has(id) && !this.feitas.has(id);
  }

  interrompidas() {
    return [...this.rodando].filter((id) => !this.feitas.has(id));
  }

  /** Antes do primeiro comando. Se não grava, nada roda. */
  marcarRodando(id) {
    this.guarda.gravar(`nm:${id}`, `rodando ${agora()}`);
    this.rodando.add(id);
  }

  marcarFeita(r) {
    this.feitas.set(r.id, r);
    const bits = (r.comandos ?? []).map((ok) => (ok ? "1" : "0")).join("");
    this.guarda.gravar(`nm:${r.id}`, `feita ${agora()} ${r.estado} ${r.motivo ?? "-"} ${bits || "-"}`);
  }
}

/** O kit como veio da API (contrato, 17.3). null = fora do formato. Item fora do formato é pulado. */
export function lerKit(o) {
  if (!o || typeof o !== "object") return null;
  const id = texto(o.id);
  const chave = texto(o.chave);
  if (!id || !ID.test(id) || !chave || !/^[a-z0-9-]{1,24}$/.test(chave)) return null;
  const itens = [];
  for (const i of Array.isArray(o.itens) ? o.itens : []) {
    const espaco = inteiro(i?.espaco, -1);
    const item = texto(i?.item);
    if (itens.length === 41 || espaco < 0 || espaco > 40 || !item || !/^[a-z0-9_]{1,64}$/.test(item)) continue;
    const encantamentos = [];
    for (const e of Array.isArray(i.encantamentos) ? i.encantamentos : []) {
      const eid = texto(e?.id);
      if (encantamentos.length < 12 && eid && /^[a-z_]{1,40}$/.test(eid)) encantamentos.push({ id: eid, nivel: entre(inteiro(e.nivel, 1), 1, 255) });
    }
    const nome = limpo(texto(i.nome), 64);
    const descricao = (texto(i.descricao) ?? "").split("\n").slice(0, 6).map((l) => limpo(l, 256)).filter(Boolean).map(cor);
    itens.push({ espaco, item, qtd: entre(inteiro(i.qtd, 1), 1, 64), encantamentos, nome: nome && cor(nome), descricao });
  }
  return { id, nome: limpo(texto(o.nome), 32) ?? chave, chave, equipar: o.equipar !== false, itens };
}

export class Nucleo {
  constructor(jogo, guarda, versao) {
    this.jogo = jogo;
    this.versao = versao;
    this.registro = new Registro(guarda);
    this.resgates = new Resgates(guarda);
    this.kits = new Kits(this, jogo);
    this.aConfirmarMap = new Map();
    this.api = null;
    this.temChave = false;
    this.esperando = [];
    this.falhasSeguidas = 0;
    this.situacao = "Ainda não consultou a NeverMiss";
    this.ultimoContato = 0;
    this.ultimaConsulta = 0;
    this.pausado = false;
    // O servidor caiu entre o "rodando" e o "feita": não roda de novo, avisa a falha.
    for (const id of this.registro.interrompidas()) this.enfileirar(this.gravar(falhou(id, "interrompida")));
  }

  /** temChave = o secrets.json tem a chave (o núcleo nunca vê o valor). */
  configurar(api, temChave) {
    this.api = api;
    this.temChave = !!temChave && api !== null;
  }

  configurado() {
    return this.temChave;
  }

  /** Um jogador entrou: se há entrega esperando por ele, o plugin adianta a consulta. */
  aoEntrar(nome) {
    const n = nome.toLowerCase();
    return this.esperando.some((e) => e.toLowerCase() === n);
  }

  /** Uma volta do laço. Devolve os segundos até a próxima (-1 = parar). */
  async ciclo() {
    if (!this.temChave) return -1;
    try {
      await this.confirmar();
      this.ultimaConsulta = agora();
      const enviados = this.esperando.filter((n) => this.jogo.online(n)).slice(0, MAX_ESPERANDO);
      const r = await this.post("/v1/minecraft/fila", this.corpoFila(enviados));
      if (r.status === 0) return this.foraDoAr(`Sem resposta da NeverMiss (${r.erro ?? "?"})`);
      if (r.status !== 200) return this.erro(r);
      this.falhasSeguidas = 0;
      this.ultimoContato = agora();
      const pausa = r.json.pausado === true;
      if (pausa && !this.pausado) this.jogo.aviso("Entregas pausadas pela NeverMiss.");
      this.pausado = pausa;
      this.esperando = (Array.isArray(r.json.esperando) ? r.json.esperando : []).filter((x) => typeof x === "string").slice(0, MAX_ESPERANDO);
      this.situacao = pausa ? "Entregas pausadas pela NeverMiss" : "Conectado";
      for (const e of this.lerEntregas(r.json.entregas)) this.enfileirar(this.executar(e));
      await this.confirmar();
      if (this.esperando.some((n) => !enviados.includes(n) && this.jogo.online(n))) return 5;
      return entre(inteiro(r.json.proximaEm, 30), 10, 300);
    } catch (x) {
      this.jogo.aviso(`Erro na consulta à NeverMiss: ${x?.message ?? x}`);
      return 60;
    }
  }

  executar(e) {
    const id = e.id;
    const guardado = this.registro.feita(id);
    if (guardado) return guardado;
    if (this.registro.interrompida(id)) return this.gravar(falhou(id, "interrompida"));
    if (e.teste) {
      this.jogo.log("Teste do painel recebido");
      return entregue(id, []);
    }
    if (e.acaoDesconhecida) {
      this.jogo.aviso(`Entrega ${id} recusada: traz o que este addon não sabe fazer. Atualize o addon NeverMiss.`);
      return falhou(id, "erro");
    }
    if (e.comandos.length === 0 && !e.kit) return entregue(id, []);
    const nome = e.nick;
    if (nome !== null && !(e.bedrock ? GAMERTAG : NICK).test(nome)) {
      this.jogo.aviso(`Entrega ${id} recusada: nick inválido`);
      return falhou(id, "bloqueado");
    }
    if (e.comandos.length > MAX_COMANDOS) return falhou(id, "bloqueado");
    const prontos = [];
    for (const c of e.comandos) {
      const p = c !== null && nome !== null ? comNome(c, nome) : c;
      if (bloqueado(p)) {
        this.jogo.aviso(`Entrega ${id} recusada: comando proibido: ${p ?? "?"}`);
        return falhou(id, "bloqueado");
      }
      prontos.push(semBarra(p));
    }
    const kit = e.kit;
    if (kit && !kit.kit) {
      this.jogo.aviso(`Entrega ${id}: o kit veio fora do formato`);
      return falhou(id, "erro");
    }
    const darAgora = kit && kit.darAgora;
    if (darAgora && nome === null) {
      this.jogo.aviso(`Entrega ${id} recusada: o kit vai para o inventário do jogador, e a entrega não tem nick`);
      return falhou(id, "kit_incompativel");
    }
    if (nome !== null && (e.soOnline || darAgora) && !this.jogo.online(nome)) return adiada(id);
    if (prontos.some((p) => p.includes("{uuid}"))) {
      // A Script API não dá o UUID nem o XUID do jogador: o {uuid} não tem como sair no BDS.
      this.jogo.aviso(`Entrega ${id}: o {uuid} não existe no BDS (a Script API não dá o UUID nem o XUID do jogador). Use {nick}.`);
      return falhou(id, "uuid_pirata");
    }
    if (darAgora && !this.jogo.kitExiste(kit.kit)) {
      this.jogo.aviso(`Entrega ${id}: nenhum item do kit ${kit.kit.nome} existe no Bedrock`);
      return falhou(id, "kit_incompativel");
    }

    try {
      // O kit dado na compra conta como o primeiro resgate (o id da entrega é o id do direito): o próximo /kit só sai depois do intervalo.
      if (darAgora && kit.resgate) this.resgates.marcar(id, agora());
      this.registro.marcarRodando(id);
    } catch (x) {
      this.jogo.aviso(`Não consegui gravar o registro no mundo, nada rodou: ${x?.message ?? x}`);
      return falhou(id, "erro");
    }
    const ok = prontos.map((p) => {
      let rodou;
      try {
        rodou = this.jogo.rodar(p);
      } catch (x) {
        this.jogo.aviso(`Erro no comando: ${x?.message ?? x}`);
        rodou = false;
      }
      this.jogo.log(`${rodou ? "Rodou" : "Falhou"}: ${p}`);
      return rodou;
    });
    const r = ok.every(Boolean) ? entregue(id, ok) : falhou(id, "comando_falhou", ok);
    if (darAgora) {
      const dados = this.jogo.darKit(nome, kit.kit, kit.vezes);
      this.jogo.log(`${nome} recebeu o kit ${kit.kit.nome} (${dados} itens)`);
      if (dados === 0) return this.gravar(falhou(id, "kit_incompativel", ok));
      this.jogo.mensagem(nome, `§aVocê recebeu o kit ${kit.kit.nome}.` + (kit.resgate ? ` Use /kit ${kit.kit.chave} a cada ${tempo(kit.resgate.intervaloS * 1000)}.` : ""));
    }
    if (kit && kit.resgate && r.estado === "entregue" && nome !== null) this.kits.sujar(nome);
    return this.gravar(r);
  }

  /**
   * Manda as confirmações guardadas, 50 por vez. Se a API não responder, ficam para a próxima volta.
   * O registro no mundo é que garante que nada roda 2 vezes.
   */
  async confirmar() {
    while (this.aConfirmarMap.size > 0 && this.temChave) {
      const lote = [...this.aConfirmarMap.values()].slice(0, MAX_LOTE);
      const r = await this.post("/v1/minecraft/confirmar", { resultados: lote.map(paraJson) });
      if (r.status === 400) this.jogo.aviso(`A NeverMiss recusou uma confirmação: ${texto(r.json.error) ?? "?"}`);
      else if (r.status !== 200) return;
      for (const x of lote) if (this.aConfirmarMap.get(x.id) === x) this.aConfirmarMap.delete(x.id);
    }
  }

  aConfirmar() {
    return this.aConfirmarMap.size;
  }

  status() {
    return [
      `NeverMiss ${this.versao} (bds): ${this.situacao}`,
      `Chave: ${this.temChave ? "no secrets.json" : "nenhuma"} · API: ${this.api ?? "?"}`,
      `Último contato: ${this.ultimoContato === 0 ? "nunca" : `há ${Math.floor((agora() - this.ultimoContato) / 1000)} s`}`,
      `Esperando o jogador entrar: ${this.esperando.length} · A confirmar: ${this.aConfirmarMap.size}`,
    ];
  }

  /** Um erro do jogo.post vira "sem resposta" (o laço nunca para). */
  async post(caminho, corpo) {
    try {
      return await this.jogo.post(this.api + caminho, JSON.stringify(corpo));
    } catch (x) {
      return { status: 0, json: {}, retry: null, erro: String(x?.message ?? x) };
    }
  }

  enfileirar(r) {
    this.aConfirmarMap.set(r.id, r);
  }

  gravar(r) {
    try {
      this.registro.marcarFeita(r);
    } catch (x) {
      this.jogo.aviso(`Não consegui gravar o registro no mundo: ${x?.message ?? x}`);
    }
    return r;
  }

  erro(r) {
    switch (r.status) {
      case 401:
        return this.parar('Chave inválida ou revogada. No secrets.json: "chave": "Bearer nmmc_..." (com o Bearer e o espaço).', 300);
      case 403:
        return this.parar("O adicional Minecraft não está ativo nesta loja.", 300);
      case 426:
        return this.parar(`Atualize o addon NeverMiss (mínima ${texto(r.json.minima) ?? "?"}).`, 600);
      case 429:
        return entre(inteiro(r.retry, 5), 5, 300);
      default:
        return this.foraDoAr(`A NeverMiss respondeu ${r.status}`);
    }
  }

  parar(motivo, segundos) {
    this.situacao = motivo;
    this.jogo.aviso(motivo);
    return segundos;
  }

  foraDoAr(motivo) {
    this.situacao = motivo;
    if (this.falhasSeguidas === 0) this.jogo.aviso(`${motivo}. Tento de novo em 30 s.`);
    this.falhasSeguidas++;
    return Math.min(300, 30 << Math.min(4, this.falhasSeguidas - 1));
  }

  corpoFila(online) {
    return {
      versao: this.versao,
      recursos: ["kit"],
      servidor: { versao: [...this.jogo.versao()].slice(0, 80).join(""), onlineMode: this.jogo.onlineMode(), plataforma: "bds", edicao: "bedrock", bedrock: null },
      online,
    };
  }

  lerEntregas(lista) {
    const entregas = [];
    for (const o of Array.isArray(lista) ? lista : []) {
      if (!o || typeof o !== "object") continue;
      const id = texto(o.id);
      if (!id || !ID.test(id)) {
        this.jogo.aviso("Entrega com id inválido ignorada");
        continue;
      }
      // Só uma ação "kit". A que este addon não conhece (ou um segundo kit) faz a entrega falhar sem rodar nada, como no Java.
      const acoes = Array.isArray(o.acoes) ? o.acoes : [];
      const acao = acoes[0] && acoes[0].tipo === "kit" ? acoes[0] : null;
      entregas.push({
        acaoDesconhecida: acoes.length > (acao ? 1 : 0),
        id,
        nick: texto(o.nick),
        soOnline: o.soOnline === true,
        teste: o.evento === "teste",
        bedrock: o.edicaoJogador === "bedrock",
        comandos: (Array.isArray(o.comandos) ? o.comandos : []).map((c) => (typeof c === "string" ? c : null)),
        kit: acao
          ? {
              darAgora: acao.darAgora === true,
              vezes: entre(inteiro(acao.vezes, 1), 1, 1000),
              kit: lerKit(acao.kit),
              resgate: acao.resgate && typeof acao.resgate === "object" ? { intervaloS: entre(inteiro(acao.resgate.intervaloS, 10), 1, 31536000) } : null,
            }
          : null,
      });
    }
    return entregas;
  }
}

const paraJson = (r) => ({ id: r.id, estado: r.estado, ...(r.motivo !== null && { motivo: r.motivo }), ...(r.comandos !== null && { comandos: r.comandos }) });

/** A hora do último /kit de cada direito, nas dynamic properties ("nmr:<id do direito>"). Gravada antes de dar. */
class Resgates {
  constructor(guarda) {
    this.guarda = guarda;
    // O intervalo vai até 365 dias: um resgate mais velho que isso já não segura nada.
    const limite = agora() - 366 * 24 * 60 * 60 * 1000;
    for (const k of guarda.ids()) if (k.startsWith("nmr:") && !(Number(guarda.ler(k)) > limite)) guarda.apagar(k);
  }

  ultimo(id) {
    return Number(this.guarda.ler(`nmr:${id}`)) || 0;
  }

  marcar(id, ms) {
    this.guarda.gravar(`nmr:${id}`, String(ms));
  }
}

/** O /kit (contrato, 17.4): quem comprou um kit com resgate pega de novo a cada intervalo, até o fim do prazo. */
export class Kits {
  constructor(nucleo, jogo) {
    this.nucleo = nucleo;
    this.jogo = jogo;
    this.copias = new Map();
    // Quem entrou e ainda não teve os kits baixados: vão juntos (até 100), numa consulta só.
    this.pedir = new Set();
    this.agendado = false;
  }

  aoEntrar(nome) {
    const n = nomeNaApi(nome);
    if (!n || !this.nucleo.configurado()) return;
    this.pedir.add(n);
    if (this.agendado) return;
    this.agendado = true;
    this.jogo.depois(2, () => this.baixarPedidos());
  }

  aoSair(nome) {
    const n = nomeNaApi(nome);
    if (n) this.copias.delete(n.toLowerCase());
  }

  /** O plugin confirmou uma entrega de kit com resgate para esse nick: o próximo /kit baixa de novo (o direito nasce na confirmação). */
  sujar(nome) {
    const c = this.copias.get((nomeNaApi(nome) ?? "").toLowerCase());
    if (c) c.velha = true;
  }

  async baixarPedidos() {
    const nicks = [...this.pedir].slice(0, 100);
    for (const n of nicks) this.pedir.delete(n);
    const novas = nicks.length ? await this.baixar(nicks) : null;
    if (novas) for (const [k, v] of novas) this.copias.set(k, v);
    this.agendado = this.pedir.size > 0;
    if (this.agendado) this.jogo.depois(2, () => this.baixarPedidos());
  }

  /** null = a NeverMiss não respondeu. */
  async baixar(nicks) {
    const r = await this.nucleo.post("/v1/minecraft/kits", { nicks });
    if (r.status !== 200) return null;
    const em = agora();
    const kits = new Map();
    for (const [k, v] of Object.entries(r.json.kits && typeof r.json.kits === "object" ? r.json.kits : {})) {
      const kit = lerKit(v);
      if (kit) kits.set(k, kit);
    }
    const porNick = new Map(nicks.map((n) => [n.toLowerCase(), []]));
    for (const d of Array.isArray(r.json.direitos) ? r.json.direitos : []) {
      const id = texto(d?.id);
      const dele = porNick.get((texto(d?.nick) ?? "").toLowerCase());
      if (!id || !dele || !kits.has(texto(d.kit))) continue;
      const ate = d.ate == null ? 0 : Date.parse(d.ate) || 0;
      dele.push({ id, kit: texto(d.kit), intervaloMs: Math.max(1, inteiro(d.intervaloS, 1)) * 1000, ate });
    }
    const pausado = r.json.pausado === true;
    return new Map([...porNick].map(([n, direitos]) => [n, { em, direitos, kits, pausado, velha: false }]));
  }

  /** /kit [nome]. Devolve a resposta ao jogador (null = ele saiu). */
  async comando(nome, pedido) {
    const n = nomeNaApi(nome);
    if (!n) return "§cEste nome de jogador não pode usar o /kit da NeverMiss.";
    if (!this.nucleo.configurado()) return "§cA NeverMiss não está configurada neste servidor.";
    let c = this.copias.get(n.toLowerCase());
    if (!c || c.velha || agora() - c.em >= 60000) {
      const novas = await this.baixar([n]);
      if (novas) this.copias.set(n.toLowerCase(), (c = novas.get(n.toLowerCase())));
    }
    if (!c) return "§cNão consegui falar com a NeverMiss agora. Tente de novo em instantes.";
    if (c.pausado) return "§cA NeverMiss pausou as entregas. Tente mais tarde.";
    if (!pedido) return this.lista(c);
    return this.resgatar(nome, c, pedido.toLowerCase());
  }

  falta(d, ms) {
    const ultimo = this.nucleo.resgates.ultimo(d.id);
    return ultimo === 0 ? 0 : Math.max(0, ultimo + d.intervaloMs - ms);
  }

  lista(c) {
    const ms = agora();
    const menor = new Map();
    for (const d of c.direitos) {
      if (d.ate !== 0 && d.ate <= ms) continue;
      const chave = c.kits.get(d.kit).chave;
      const f = this.falta(d, ms);
      if (!menor.has(chave) || f < menor.get(chave)) menor.set(chave, f);
    }
    if (menor.size === 0) return "§eVocê não tem kits da NeverMiss.";
    return `§aSeus kits: ${[...menor].map(([k, f]) => `${k} (${f === 0 ? "agora" : `em ${tempo(f)}`})`).join(", ")}. Use /kit <nome>.`;
  }

  /** Confere o intervalo e marca o resgate na mesma volta, então dois /kit seguidos não dão dois kits. */
  resgatar(nome, c, pedido) {
    const ms = agora();
    let kit = null;
    let livre = null;
    let falta = Infinity;
    for (const d of c.direitos) {
      const k = c.kits.get(d.kit);
      if (k.chave !== pedido || (d.ate !== 0 && d.ate <= ms)) continue;
      kit = k;
      const f = this.falta(d, ms);
      if (f === 0) {
        livre = d;
        break;
      }
      falta = Math.min(falta, f);
    }
    if (!kit) return "§cVocê não tem esse kit. Use /kit para ver os seus.";
    if (!livre) return `§eEspere ${tempo(falta)} para pegar o kit ${kit.nome} de novo.`;
    if (!this.jogo.online(nome)) return null;
    if (!this.jogo.kitExiste(kit)) return "§cNenhum item deste kit existe no Bedrock.";
    try {
      this.nucleo.resgates.marcar(livre.id, ms);
    } catch (x) {
      this.jogo.aviso(`Não consegui gravar o resgate no mundo, o kit não saiu: ${x?.message ?? x}`);
      return "§cNão consegui guardar o resgate. Tente de novo.";
    }
    const dados = this.jogo.darKit(nome, kit, 1);
    this.jogo.log(`${nome} pegou o kit ${kit.nome} (/kit, ${dados} itens)`);
    return `§aVocê pegou o kit ${kit.nome}.`;
  }
}
