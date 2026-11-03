// Imita as rotas do plugin na API da NeverMiss (contrato, seções 4 e 17), como o ApiFalsa.java: fila com lease, o início
// (iniciar) e a entrega que volta com iniciada, confirmação idempotente, só online, kits, limites e erros. Usada no teste do
// laço (teste/nucleo-teste.mjs) e no teste/e2e.sh.
// Uso: node teste/api-falsa.mjs <porta> <chave> [leaseMs] [proximaEm] [intervaloMs]
// Rotas de controle (só para teste): POST /teste/entregas, /teste/cortar, /teste/vencer (o lease), /teste/ajustar, /teste/limpar e /teste/estado.
// No /teste/ajustar: cortarInicio = quantos iniciar respondem 502 sem gravar; perderInicio = quantos gravam e respondem 502.
import http from "node:http";

const MOTIVOS = new Set(["comando_falhou", "bloqueado", "uuid_pirata", "nick_nao_existe", "interrompida", "erro", "sem_bedrock", "kit_incompativel"]);
const [porta = "4011", chave, leaseMs = "120000", proximaEm = "30", intervaloMs = "0"] = process.argv.slice(2);
if (!chave) {
  console.error("Uso: node api-falsa.mjs <porta> <chave> [leaseMs] [proximaEm] [intervaloMs]");
  process.exit(2);
}

let s;
const limpar = () => {
  s = { entregas: new Map(), pulls: [], confirmacoes: 0, agentes: [], leaseMs: +leaseMs, intervaloMs: +intervaloMs, proximaEm: +proximaEm, adicional: true, pausado: false, minima: "1.0.0", cortar: 0, cortarInicio: 0, perderInicio: 0, inicios: [], esperandoExtra: 0, ultimaConsulta: 0, kits: { kits: {}, direitos: [], pausado: false }, kitsPedidos: [] };
};
limpar();

const comparar = (a, b) => {
  const x = String(a).split(".").map(Number), y = String(b).split(".").map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] || 0) - (y[i] || 0);
    if (d) return d;
  }
  return 0;
};
const erro = (error, codigo, extra = {}) => ({ error, codigo, ...extra });

function fila(corpo, res) {
  if (comparar(corpo.versao, s.minima) < 0) return [426, erro("Plugin velho", "plugin_velho", { minima: s.minima })];
  const agora = Date.now();
  if (agora - s.ultimaConsulta < s.intervaloMs) {
    res.setHeader("Retry-After", String(Math.ceil((s.intervaloMs - (agora - s.ultimaConsulta)) / 1000)));
    return [429, erro("Cedo", "cedo")];
  }
  s.ultimaConsulta = agora;
  s.pulls.push(corpo);
  const online = new Set(corpo.online.map((n) => n.toLowerCase()));
  const entregas = [], esperando = [];
  if (!s.pausado) {
    for (const e of s.entregas.values()) {
      const livre = e.estado === "pendente" || (e.estado === "entregando" && e.leaseAte <= agora);
      if (!livre || entregas.length === 20) continue;
      if (e.soOnline && !online.has((e.nick ?? "").toLowerCase())) {
        esperando.push(e.nick);
        continue;
      }
      if (e.tentativas >= 5) {
        Object.assign(e, { estado: "falhou", motivo: "sem_resposta" });
        continue;
      }
      Object.assign(e, { estado: "entregando", leaseAte: agora + s.leaseMs, tentativas: e.tentativas + 1 });
      entregas.push({ id: e.id, evento: e.evento, nick: e.nick, edicaoJogador: e.edicaoJogador, comandos: e.comandos, soOnline: e.soOnline, ...(e.acoes && { acoes: e.acoes }), ...(e.iniciada && { iniciada: true }) });
    }
  }
  for (let i = 0; i < s.esperandoExtra; i++) esperando.push(`Espera${i}`);
  return [200, { entregas, esperando, proximaEm: s.proximaEm, pausado: s.pausado }];
}

// O início antes do primeiro comando: só a entrega saindo começa; repetir dá 200 e não muda nada
function iniciar(corpo) {
  if (s.cortarInicio > 0) return s.cortarInicio--, [502, {}];
  const e = s.entregas.get(corpo.id);
  if (!e) return [404, erro("Não existe", "nao_encontrada")];
  if (e.estado !== "entregando") return [409, erro("Não está saindo", "estado")];
  e.iniciada = true;
  s.inicios.push(e.id);
  if (s.perderInicio > 0) return s.perderInicio--, [502, {}];
  return [200, { ok: true }];
}

function confirmar(corpo) {
  if (s.cortar > 0) {
    s.cortar--;
    return [502, {}];
  }
  if (!Array.isArray(corpo.resultados) || corpo.resultados.length < 1 || corpo.resultados.length > 50) return [400, erro("Corpo fora do formato", "corpo")];
  s.confirmacoes++;
  const ok = [], ignorados = [];
  for (const r of corpo.resultados) {
    const e = s.entregas.get(r.id);
    const motivo = !e ? "nao_encontrada" : e.estado === "pendente" || e.estado === "entregando" ? null : "ja_final";
    if (motivo) {
      ignorados.push({ id: r.id, motivo });
      continue;
    }
    e.resultado = Array.isArray(r.comandos) ? r.comandos : null;
    if (r.estado === "adiada") Object.assign(e, { estado: "pendente", tentativas: e.tentativas - 1, iniciada: false });
    else if (r.estado === "entregue" && e.resultado?.includes(false)) Object.assign(e, { estado: "falhou", motivo: "comando_falhou" });
    else if (r.estado === "entregue") e.estado = "entregue";
    else Object.assign(e, { estado: "falhou", motivo: MOTIVOS.has(r.motivo) ? r.motivo : "erro" });
    ok.push(r.id);
  }
  return [200, { ok, ignorados }];
}

function controle(caminho, corpo) {
  if (caminho === "/teste/entregas") {
    for (const c of Array.isArray(corpo) ? corpo : [corpo]) {
      s.entregas.set(c.id, { id: c.id, nick: c.nick ?? null, soOnline: !!c.soOnline, evento: c.evento ?? "entrega", edicaoJogador: c.edicaoJogador ?? "java", comandos: c.comandos ?? [], acoes: c.acoes ?? null, iniciada: false, estado: c.estado ?? "pendente", motivo: null, leaseAte: 0, tentativas: 0, resultado: null });
    }
    return [201, {}];
  }
  if (caminho === "/teste/cortar") return s.cortar++, [200, {}];
  if (caminho === "/teste/vencer") return s.entregas.forEach((e) => (e.leaseAte = 0)), [200, {}];
  if (caminho === "/teste/ajustar") return Object.assign(s, corpo), [200, {}];
  if (caminho === "/teste/limpar") return limpar(), [200, {}];
  const entregas = {};
  for (const e of s.entregas.values()) entregas[e.id] = { estado: e.estado, motivo: e.motivo, tentativas: e.tentativas, resultado: e.resultado, iniciada: e.iniciada };
  return [200, { entregas, pulls: s.pulls.length, ultimoPull: s.pulls.at(-1) ?? null, confirmacoes: s.confirmacoes, agentes: [...new Set(s.agentes)], kitsPedidos: s.kitsPedidos, inicios: s.inicios }];
}

http
  .createServer((req, res) => {
    const partes = [];
    req.on("data", (p) => partes.push(p));
    req.on("end", () => {
      let resposta;
      try {
        const texto = Buffer.concat(partes).toString("utf8");
        const corpo = texto ? JSON.parse(texto) : {};
        const caminho = new URL(req.url, "http://x").pathname;
        if (caminho.startsWith("/teste/")) resposta = controle(caminho, corpo);
        // Com ou sem o "Bearer ", como a API (o addon do BDS manda o secrets.json inteiro)
        else if (req.headers.authorization !== `Bearer ${chave}` && req.headers.authorization !== chave) resposta = [401, erro("Chave inválida", "chave")];
        else if (!s.adicional) resposta = [403, erro("Sem o adicional", "sem_adicional")];
        else {
          s.agentes.push(req.headers["user-agent"]);
          if (caminho === "/v1/minecraft/fila") resposta = fila(corpo, res);
          else if (caminho === "/v1/minecraft/fila/iniciar") resposta = iniciar(corpo);
          else if (caminho === "/v1/minecraft/confirmar") resposta = confirmar(corpo);
          else if (caminho === "/v1/minecraft/kits") resposta = (s.kitsPedidos.push(corpo.nicks), [200, s.kits]);
          else resposta = [404, erro("Não existe", "rota")];
        }
      } catch (e) {
        resposta = [400, erro(String(e), "corpo")];
      }
      res.writeHead(resposta[0], { "Content-Type": "application/json" });
      res.end(JSON.stringify(resposta[1]));
    });
  })
  .listen(+porta, "127.0.0.1", () => console.log(`API falsa em http://127.0.0.1:${porta}`));
