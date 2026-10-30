package com.nevermissapps.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imita as rotas do plugin na API da NeverMiss (contrato, seção 4): fila com lease,
 * confirmação idempotente, só online, limites e erros. Usada nos testes e no teste/e2e.sh.
 *
 * Rotas de controle (só para teste): POST /teste/entregas, GET /teste/estado e POST /teste/cortar.
 */
public final class ApiFalsa implements AutoCloseable {
    private static final Set<String> MOTIVOS = new HashSet<>(Arrays.asList(
            "comando_falhou", "bloqueado", "uuid_pirata", "nick_nao_existe", "interrompida", "erro", "sem_bedrock",
            "kit_incompativel"));

    final String chave;
    final HttpServer http;
    final Map<String, Entrega> entregas = new LinkedHashMap<>();
    final List<JsonObject> pulls = new ArrayList<>();
    // O /kit (contrato, 17.4): o direito nasce quando a entrega do kit com resgate é confirmada
    final List<JsonObject> direitos = new ArrayList<>();
    final Map<String, JsonObject> kits = new LinkedHashMap<>();
    int pedidosDeKits;
    long leaseMs = 120_000;
    long intervaloMs = 0;
    int proximaEm = 30;
    boolean adicional = true;
    boolean pausado;
    String minima = "1.0.0";
    int cortar;
    private long ultimaConsulta;

    static final class Entrega {
        String id, nick, evento = "entrega", estado = "pendente", motivo, edicaoJogador = "java";
        List<String> comandos = new ArrayList<>();
        JsonArray acoes;
        boolean soOnline;
        long leaseAte;
        int tentativas;
        JsonArray resultado;
    }

    ApiFalsa(int porta, String chave) throws IOException {
        this.chave = chave;
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", porta), 0);
        http.createContext("/", this::tratar);
        http.start();
    }

    String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    synchronized Entrega adicionar(String id, String nick, boolean soOnline, String... comandos) {
        Entrega e = new Entrega();
        e.id = id;
        e.nick = nick;
        e.soOnline = soOnline;
        e.comandos.addAll(Arrays.asList(comandos));
        entregas.put(id, e);
        return e;
    }

    synchronized Entrega entrega(String id) {
        return entregas.get(id);
    }

    @Override
    public void close() {
        http.stop(0);
    }

    private synchronized void tratar(HttpExchange x) throws IOException {
        try {
            String caminho = x.getRequestURI().getPath();
            JsonObject corpo = ler(x.getRequestBody());
            if (caminho.startsWith("/teste/")) {
                controle(x, caminho, corpo);
                return;
            }
            String auth = x.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + chave).equals(auth)) {
                responder(x, 401, erro("Chave inválida", "chave"));
            } else if (!adicional) {
                responder(x, 403, erro("Sem o adicional", "sem_adicional"));
            } else if (caminho.equals("/v1/minecraft/fila")) {
                fila(x, corpo);
            } else if (caminho.equals("/v1/minecraft/confirmar")) {
                confirmar(x, corpo);
            } else if (caminho.equals("/v1/minecraft/kits")) {
                kits(x, corpo);
            } else {
                responder(x, 404, erro("Não existe", "rota"));
            }
        } catch (RuntimeException e) {
            responder(x, 400, erro(String.valueOf(e), "corpo"));
        }
    }

    private void fila(HttpExchange x, JsonObject corpo) throws IOException {
        if (comparar(corpo.get("versao").getAsString(), minima) < 0) {
            JsonObject o = erro("Plugin velho", "plugin_velho");
            o.addProperty("minima", minima);
            responder(x, 426, o);
            return;
        }
        long agora = System.currentTimeMillis();
        if (agora - ultimaConsulta < intervaloMs) {
            x.getResponseHeaders().set("Retry-After", String.valueOf((intervaloMs - (agora - ultimaConsulta) + 999) / 1000));
            responder(x, 429, erro("Cedo", "cedo"));
            return;
        }
        ultimaConsulta = agora;
        pulls.add(corpo);
        Set<String> online = new HashSet<>();
        for (JsonElement n : corpo.getAsJsonArray("online")) online.add(n.getAsString());

        JsonArray saida = new JsonArray();
        JsonArray esperando = new JsonArray();
        if (!pausado) {
            for (Entrega e : entregas.values()) {
                boolean livre = e.estado.equals("pendente") || (e.estado.equals("entregando") && e.leaseAte <= agora);
                if (!livre || saida.size() == 20) continue;
                if (e.soOnline && !online.contains(e.nick)) {
                    esperando.add(new JsonPrimitive(e.nick));
                    continue;
                }
                if (e.tentativas >= 5) {
                    e.estado = "falhou";
                    e.motivo = "sem_resposta";
                    continue;
                }
                e.estado = "entregando";
                e.leaseAte = agora + leaseMs;
                e.tentativas++;
                JsonObject o = new JsonObject();
                o.addProperty("id", e.id);
                o.addProperty("nick", e.nick);
                o.addProperty("evento", e.evento);
                o.addProperty("soOnline", e.soOnline);
                o.addProperty("edicaoJogador", e.edicaoJogador);
                JsonArray cs = new JsonArray();
                for (String c : e.comandos) cs.add(new JsonPrimitive(c));
                o.add("comandos", cs);
                if (e.acoes != null) o.add("acoes", e.acoes);
                saida.add(o);
            }
        }
        JsonObject r = new JsonObject();
        r.add("entregas", saida);
        r.add("esperando", esperando);
        r.addProperty("proximaEm", proximaEm);
        r.addProperty("pausado", pausado);
        responder(x, 200, r);
    }

    private void confirmar(HttpExchange x, JsonObject corpo) throws IOException {
        if (cortar > 0) {
            cortar--;
            responder(x, 502, new JsonObject());
            return;
        }
        JsonArray ok = new JsonArray();
        JsonArray ignorados = new JsonArray();
        for (JsonElement el : corpo.getAsJsonArray("resultados")) {
            JsonObject r = el.getAsJsonObject();
            String id = r.get("id").getAsString();
            Entrega e = entregas.get(id);
            String motivo = e == null ? "nao_encontrada"
                    : (e.estado.equals("pendente") || e.estado.equals("entregando")) ? null : "ja_final";
            if (motivo != null) {
                JsonObject i = new JsonObject();
                i.addProperty("id", id);
                i.addProperty("motivo", motivo);
                ignorados.add(i);
                continue;
            }
            String estado = r.get("estado").getAsString();
            e.resultado = r.has("comandos") ? r.getAsJsonArray("comandos") : null;
            if (estado.equals("adiada")) {
                e.estado = "pendente";
                e.tentativas--;
            } else if (estado.equals("entregue") && !tudoTrue(e.resultado)) {
                e.estado = "falhou";
                e.motivo = "comando_falhou";
            } else if (estado.equals("entregue")) {
                e.estado = "entregue";
                criarDireito(e);
            } else {
                e.estado = "falhou";
                String m = r.has("motivo") ? r.get("motivo").getAsString() : "erro";
                e.motivo = MOTIVOS.contains(m) ? m : "erro";
            }
            ok.add(new JsonPrimitive(id));
        }
        JsonObject resp = new JsonObject();
        resp.add("ok", ok);
        resp.add("ignorados", ignorados);
        responder(x, 200, resp);
    }

    private void criarDireito(Entrega e) {
        if (e.acoes == null) return;
        for (JsonElement a : e.acoes) {
            JsonObject o = a.getAsJsonObject();
            if (!o.has("resgate") || !o.get("resgate").isJsonObject()) continue;
            JsonObject kit = o.getAsJsonObject("kit");
            JsonObject r = o.getAsJsonObject("resgate");
            JsonObject d = new JsonObject();
            d.addProperty("id", e.id);
            d.addProperty("nick", e.nick);
            d.addProperty("kit", kit.get("id").getAsString());
            d.add("intervaloS", r.get("intervaloS"));
            d.add("ate", r.get("ate"));
            direitos.add(d);
            kits.put(kit.get("id").getAsString(), kit);
        }
    }

    private void kits(HttpExchange x, JsonObject corpo) throws IOException {
        pedidosDeKits++;
        Set<String> nicks = new HashSet<>();
        for (JsonElement n : corpo.getAsJsonArray("nicks")) nicks.add(n.getAsString().toLowerCase());
        JsonArray ds = new JsonArray();
        JsonObject ks = new JsonObject();
        for (JsonObject d : direitos) {
            if (pausado || !nicks.contains(d.get("nick").getAsString().toLowerCase())) continue;
            ds.add(d);
            ks.add(d.get("kit").getAsString(), kits.get(d.get("kit").getAsString()));
        }
        JsonObject r = new JsonObject();
        r.add("kits", ks);
        r.add("direitos", ds);
        r.addProperty("pausado", pausado);
        responder(x, 200, r);
    }

    private void controle(HttpExchange x, String caminho, JsonObject corpo) throws IOException {
        if (caminho.equals("/teste/entregas")) {
            Entrega e = adicionar(corpo.get("id").getAsString(),
                    corpo.has("nick") ? corpo.get("nick").getAsString() : null,
                    corpo.has("soOnline") && corpo.get("soOnline").getAsBoolean());
            if (corpo.has("evento")) e.evento = corpo.get("evento").getAsString();
            if (corpo.has("edicaoJogador")) e.edicaoJogador = corpo.get("edicaoJogador").getAsString();
            for (JsonElement c : corpo.getAsJsonArray("comandos")) e.comandos.add(c.getAsString());
            if (corpo.has("acoes")) e.acoes = corpo.getAsJsonArray("acoes");
            responder(x, 201, new JsonObject());
        } else if (caminho.equals("/teste/cortar")) {
            cortar++;
            responder(x, 200, new JsonObject());
        } else {
            JsonObject todas = new JsonObject();
            for (Entrega e : entregas.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("estado", e.estado);
                o.addProperty("motivo", e.motivo);
                o.addProperty("tentativas", e.tentativas);
                o.add("resultado", e.resultado);
                todas.add(e.id, o);
            }
            JsonObject r = new JsonObject();
            r.add("entregas", todas);
            r.addProperty("pulls", pulls.size());
            r.add("ultimoPull", pulls.isEmpty() ? null : pulls.get(pulls.size() - 1).get("servidor"));
            r.add("recursos", pulls.isEmpty() ? null : pulls.get(pulls.size() - 1).get("recursos"));
            responder(x, 200, r);
        }
    }

    private static boolean tudoTrue(JsonArray a) {
        if (a == null) return true;
        for (JsonElement b : a) if (!b.getAsBoolean()) return false;
        return true;
    }

    private static int comparar(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int d = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (d != 0) return d;
        }
        return 0;
    }

    private static JsonObject erro(String texto, String codigo) {
        JsonObject o = new JsonObject();
        o.addProperty("error", texto);
        o.addProperty("codigo", codigo);
        return o;
    }

    private static JsonObject ler(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
        String texto = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        return texto.isEmpty() ? new JsonObject() : JsonParser.parseString(texto).getAsJsonObject();
    }

    private static void responder(HttpExchange x, int status, JsonObject corpo) throws IOException {
        byte[] b = corpo.toString().getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(b);
        }
    }

    /** java ApiFalsa <porta> <chave> [leaseMs] [proximaEm]: para o teste com um servidor de verdade. */
    public static void main(String[] args) throws Exception {
        ApiFalsa api = new ApiFalsa(Integer.parseInt(args[0]), args[1]);
        if (args.length > 2) api.leaseMs = Long.parseLong(args[2]);
        if (args.length > 3) api.proximaEm = Integer.parseInt(args[3]);
        api.intervaloMs = 5_000;
        System.out.println("API falsa em " + api.url());
        Thread.currentThread().join();
    }
}
