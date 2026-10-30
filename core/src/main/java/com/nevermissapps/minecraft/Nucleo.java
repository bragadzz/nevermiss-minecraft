package com.nevermissapps.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * O laço de entrega: pergunta à API se há entregas, roda os comandos no console e confirma.
 * Não conhece a plataforma (ver {@link Jogo}), para dar para testar sem servidor.
 */
final class Nucleo {
    static final String API_PADRAO = "https://api-website.nevermissapps.com";
    static final Pattern CHAVE = Pattern.compile("^nmmc_[A-Za-z0-9_-]{43}$");
    static final Pattern NICK = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CONTROLE = Pattern.compile("\\p{Cc}");
    // A mesma lista da API. Nem o painel nem a API desligam esta checagem.
    // O EssentialsX aceita cada um com "e" na frente (esudo, eban).
    // end, shutdown, greload e velocity (velocity reload, velocity dump) são o desligar e o recarregar dos proxies.
    private static final Set<String> PROIBIDOS = new HashSet<>(Arrays.asList(
            "op", "deop", "stop", "restart", "reload", "rl", "ban", "ban-ip", "banip", "ipban", "tempban",
            "tempbanip", "tempipban", "pardon", "pardon-ip", "pardonip", "unban", "unbanip", "whitelist",
            "execute", "sudo", "plugman", "end", "shutdown", "greload", "velocity"));
    private static final Pattern ASPAS = Pattern.compile("^[\"']+|[\"']+$");
    private static final int MAX_COMANDOS = 100;
    // Quantos nicks esperando o jogador entrar a API manda (e o plugin devolve os que estão online).
    private static final int MAX_ESPERANDO = 1000;
    private static final int MAX_LOTE = 50;

    private final Jogo jogo;
    private final Registro registro;
    private final String versao;
    private final Map<String, Resultado> aConfirmar = new LinkedHashMap<>();
    // Entrega de kit com resgate que rodou → nick: confirmada, o direito do /kit nasce na API.
    private final Map<String, String> comResgate = new ConcurrentHashMap<>();
    final Kits kits;
    private volatile String api;
    private volatile String chave;
    private volatile List<String> esperando = Collections.emptyList();
    private int falhasSeguidas;

    volatile String situacao = "Ainda não consultou a NeverMiss";
    volatile long ultimoContato;
    volatile long ultimaConsulta;
    volatile boolean pausado;

    Nucleo(Jogo jogo, Registro registro, Resgates resgates, String versao) {
        this.jogo = jogo;
        this.registro = registro;
        this.versao = versao;
        kits = new Kits(jogo, this, resgates);
        // O servidor caiu entre o "rodando" e o "feita": não roda de novo, avisa a falha.
        for (String id : registro.interrompidas()) enfileirar(gravar(Resultado.falhou(id, "interrompida", null)));
    }

    /** Chave null para o laço (sem chave ou config errada). */
    void configurar(String api, String chave) {
        this.api = api;
        this.chave = chave;
    }

    boolean configurado() {
        return chave != null;
    }

    /** Só https. http só em localhost, para teste. Devolve null se não serve. */
    static String validarApi(String texto) {
        if (texto == null) return null;
        String limpo = texto.trim();
        while (limpo.endsWith("/")) limpo = limpo.substring(0, limpo.length() - 1);
        try {
            URL url = new URL(limpo);
            String host = url.getHost();
            if (url.getProtocol().equals("https") && !host.isEmpty()) return limpo;
            if (url.getProtocol().equals("http") && (host.equals("localhost") || host.equals("127.0.0.1"))) return limpo;
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    static boolean bloqueado(String comando) {
        if (comando == null || comando.trim().isEmpty() || comando.length() > 256) return true;
        if (CONTROLE.matcher(comando).find()) return true;
        // As aspas saem antes de comparar: o LuckPerms aceita "*" entre aspas.
        String[] palavras = semBarra(comando).toLowerCase(Locale.ROOT).split("\\s+");
        for (int i = 0; i < palavras.length; i++) palavras[i] = ASPAS.matcher(palavras[i]).replaceAll("");
        String primeira = palavras[0].substring(palavras[0].indexOf(':') + 1);
        if (PROIBIDOS.contains(primeira)) return true;
        if (primeira.startsWith("e") && PROIBIDOS.contains(primeira.substring(1))) return true;
        for (String p : palavras) if (p.equals("*") || p.endsWith(".*")) return true;
        return false;
    }

    private static String semBarra(String comando) {
        String c = comando.trim();
        return c.startsWith("/") ? c.substring(1) : c;
    }

    /** Um jogador entrou: se há entrega esperando por ele, o plugin adianta a consulta. */
    boolean aoEntrar(String nick) {
        for (String n : esperando) if (n.equalsIgnoreCase(nick) || nick.equalsIgnoreCase(nomeBedrock(n))) return true;
        return false;
    }

    /** O nome no servidor do jogador Bedrock com esse gamertag. null sem Floodgate ou se não é um gamertag. */
    private String nomeBedrock(String gamertag) {
        Bedrock b = jogo.bedrock();
        return b != null && Bedrock.GAMERTAG.matcher(gamertag).matches() ? b.nome(gamertag) : null;
    }

    /** A lista "esperando" não diz a edição: vale o nick Java ou o gamertag Bedrock. */
    private boolean online(String nick) {
        String b = nomeBedrock(nick);
        return jogo.online(nick) || (b != null && jogo.online(b));
    }

    /** Uma volta do laço, fora da thread principal. Devolve em quantos segundos fazer a próxima (-1 = parar). */
    int ciclo() {
        if (chave == null) return -1;
        confirmar();
        ultimaConsulta = System.currentTimeMillis();
        List<String> enviados = new ArrayList<>();
        for (String n : esperando) if (enviados.size() < MAX_ESPERANDO && online(n)) enviados.add(n);

        Resposta r;
        try {
            r = post("/v1/minecraft/fila", corpoFila(enviados));
        } catch (IOException e) {
            return foraDoAr("Sem resposta da NeverMiss (" + e.getClass().getSimpleName() + ")");
        }
        if (r.status != 200) return erro(r);

        falhasSeguidas = 0;
        ultimoContato = System.currentTimeMillis();
        boolean pausaAgora = booleano(r.json.get("pausado"));
        if (pausaAgora && !pausado) jogo.aviso("Entregas pausadas pela NeverMiss.");
        pausado = pausaAgora;
        esperando = textos(r.json.get("esperando"));
        situacao = pausado ? "Entregas pausadas pela NeverMiss" : "Conectado";

        final List<Entrega> entregas = lerEntregas(r.json.get("entregas"));
        for (Entrega e : entregas) {
            if (e.precisaUuid() && e.nick != null && jogo.onlineMode() && registro.feita(e.id) == null) {
                try {
                    e.uuid = uuidDe(e);
                } catch (Exception x) {
                    // Mojang ou GeyserMC fora do ar (ou o gamertag com espaço offline): é erro, não "nick não existe".
                    e.uuidFalhou = true;
                    jogo.aviso("Não achei o UUID de " + e.nick + ": " + (x.getCause() != null ? x.getCause() : x));
                }
            }
        }
        if (!entregas.isEmpty()) {
            List<Resultado> feitos;
            try {
                feitos = jogo.naPrincipal(() -> {
                    List<Resultado> l = new ArrayList<>();
                    for (Entrega e : entregas) l.add(executar(e));
                    return l;
                });
            } catch (Exception x) {
                // Servidor desligando: o que não rodou volta na próxima consulta.
                return 30;
            }
            for (Resultado f : feitos) enfileirar(f);
            confirmar();
        }

        for (String n : esperando) if (!enviados.contains(n) && online(n)) return 5;
        return Math.max(10, Math.min(300, inteiro(r.json.get("proximaEm"), 30)));
    }

    /** Thread principal. */
    Resultado executar(Entrega e) {
        Resultado guardado = registro.feita(e.id);
        if (guardado != null) return guardado;
        if (registro.interrompida(e.id)) return gravar(Resultado.falhou(e.id, "interrompida", null));
        if (e.teste) {
            jogo.log("Teste do painel recebido");
            return Resultado.entregue(e.id, new boolean[0]);
        }
        if (e.acaoDesconhecida) {
            jogo.aviso("Entrega " + e.id + " recusada: traz o que este plugin não sabe fazer. Atualize o plugin NeverMiss.");
            return Resultado.falhou(e.id, "erro", null);
        }
        boolean darKit = e.kit != null && e.darAgora;
        if (e.kit != null && e.intervaloMs > 0 && e.nick != null && !e.bedrock) comResgate.put(e.id, e.nick);
        if (e.comandos.isEmpty() && !darKit) return Resultado.entregue(e.id, new boolean[0]);
        if (e.nick != null && !(e.bedrock ? Bedrock.GAMERTAG : NICK).matcher(e.nick).matches()) {
            jogo.aviso("Entrega " + e.id + " recusada: nick inválido");
            return Resultado.falhou(e.id, "bloqueado", null);
        }
        if (e.comandos.size() > MAX_COMANDOS) return Resultado.falhou(e.id, "bloqueado", null);
        String nome = e.nick;
        if (e.bedrock && e.nick != null) {
            if (jogo.bedrock() == null) {
                jogo.aviso("Entrega " + e.id + " recusada: este servidor não aceita Bedrock: instale Geyser + Floodgate");
                return Resultado.falhou(e.id, "sem_bedrock", null);
            }
            nome = jogo.bedrock().nome(e.nick);
        }
        List<String> prontos = new ArrayList<>();
        for (String c : e.comandos) {
            // No Bedrock a API manda o {nick} para o plugin trocar pelo nome no servidor (com o prefixo do Floodgate).
            String p = c != null && e.bedrock && nome != null ? c.replace("{nick}", nome) : c;
            if (bloqueado(p)) {
                jogo.aviso("Entrega " + e.id + " recusada: comando proibido: " + (p == null ? "?" : p));
                return Resultado.falhou(e.id, "bloqueado", null);
            }
            prontos.add(semBarra(p));
        }
        if (darKit && (jogo.versaoMc() == null || nome == null)) {
            jogo.aviso("Entrega " + e.id + " recusada: o kit vai para o inventário do jogador, e " + (nome == null
                    ? "a entrega não tem nick" : "o proxy não tem inventário: entregue o kit pelo plugin do servidor de jogo"));
            return Resultado.falhou(e.id, "kit_incompativel", null);
        }
        // O item só entra no inventário com o jogador no servidor.
        if ((e.soOnline || darKit) && nome != null && !jogo.online(nome)) return Resultado.adiada(e.id);
        if (darKit && !e.kit.algumExiste(jogo)) {
            jogo.aviso("Entrega " + e.id + " recusada: nenhum item do kit " + e.kit.nome + " existe na versão deste servidor");
            return Resultado.falhou(e.id, "kit_incompativel", null);
        }

        if (e.precisaUuid()) {
            if (!jogo.onlineMode()) return Resultado.falhou(e.id, "uuid_pirata", null);
            // O UUID do Floodgate vem do xuid (não é versão 4).
            if (e.uuid == null && e.uuidFalhou) return Resultado.falhou(e.id, "erro", null);
            if (e.uuid == null || (!e.bedrock && e.uuid.version() != 4)) return Resultado.falhou(e.id, "nick_nao_existe", null);
            for (int i = 0; i < prontos.size(); i++) prontos.set(i, prontos.get(i).replace("{uuid}", e.uuid.toString()));
        }

        try {
            // O kit dado na compra conta como o primeiro /kit: o próximo só sai depois do intervalo.
            if (darKit && e.intervaloMs > 0) kits.resgates.marcar(e.id, kits.relogio.getAsLong());
            registro.marcarRodando(e.id);
        } catch (IOException x) {
            jogo.aviso("Não consegui gravar executadas.txt ou resgates.txt, nada rodou: " + x.getMessage());
            return Resultado.falhou(e.id, "erro", null);
        }
        boolean[] ok = new boolean[prontos.size()];
        boolean tudo = true;
        for (int i = 0; i < ok.length; i++) {
            try {
                ok[i] = jogo.rodar(prontos.get(i));
            } catch (RuntimeException x) {
                jogo.aviso("Erro no comando: " + x);
            }
            jogo.log((ok[i] ? "Rodou: " : "Falhou: ") + prontos.get(i));
            tudo &= ok[i];
        }
        if (darKit) {
            int dados = kits.dar(e.kit, nome, e.vezes);
            jogo.log("Kit " + e.kit.nome + " para " + nome + ": " + dados + " itens");
            if (dados == 0) return gravar(Resultado.falhou(e.id, "kit_incompativel", ok));
            jogo.mensagem(nome, "\u00a7aVocê recebeu o kit " + e.kit.nome + "."
                    + (e.intervaloMs > 0 ? " Use /kit " + e.kit.chave + " a cada " + Kits.tempo(e.intervaloMs) + "." : ""));
        }
        return gravar(tudo ? Resultado.entregue(e.id, ok) : Resultado.falhou(e.id, "comando_falhou", ok));
    }

    /** Manda as confirmações guardadas. Se a API não responder, ficam para a próxima volta. */
    void confirmar() {
        while (true) {
            List<Resultado> lote = new ArrayList<>();
            synchronized (aConfirmar) {
                for (Resultado x : aConfirmar.values()) {
                    if (lote.size() == MAX_LOTE) break;
                    lote.add(x);
                }
            }
            if (lote.isEmpty() || chave == null) return;
            JsonArray itens = new JsonArray();
            for (Resultado x : lote) itens.add(x.json());
            JsonObject corpo = new JsonObject();
            corpo.add("resultados", itens);
            Resposta r;
            try {
                r = post("/v1/minecraft/confirmar", corpo);
            } catch (IOException e) {
                return;
            }
            if (r.status == 400) jogo.aviso("A NeverMiss recusou uma confirmação: " + texto(r.json.get("error")));
            else if (r.status != 200) return;
            synchronized (aConfirmar) {
                for (Resultado x : lote) aConfirmar.remove(x.id, x);
            }
            for (Resultado x : lote) {
                String nick = comResgate.remove(x.id);
                if (nick != null) kits.sujar(nick);
            }
        }
    }

    int aConfirmar() {
        synchronized (aConfirmar) {
            return aConfirmar.size();
        }
    }

    List<String> status() {
        long agora = System.currentTimeMillis();
        String c = chave;
        Bedrock b = jogo.bedrock();
        return Arrays.asList(
                "NeverMiss " + versao + " (" + jogo.plataforma() + "): " + situacao,
                "Chave: " + (c == null ? "nenhuma" : "****" + c.substring(c.length() - 4)) + " · API: " + api,
                "Último contato: " + (ultimoContato == 0 ? "nunca" : "há " + (agora - ultimoContato) / 1000 + " s"),
                "Esperando o jogador entrar: " + esperando.size() + " · A confirmar: " + aConfirmar(),
                "Bedrock: " + (b == null ? "não (instale Geyser + Floodgate)" : "sim, pelo Floodgate (prefixo \"" + b.prefixo + "\")"));
    }

    private void enfileirar(Resultado r) {
        synchronized (aConfirmar) {
            aConfirmar.put(r.id, r);
        }
    }

    private Resultado gravar(Resultado r) {
        try {
            registro.marcarFeita(r);
        } catch (IOException x) {
            jogo.aviso("Não consegui gravar executadas.txt: " + x.getMessage());
        }
        return r;
    }

    private int erro(Resposta r) {
        switch (r.status) {
            case 401:
                return parar("Chave inválida ou revogada. Gere outra no painel.", 300);
            case 403:
                return parar("O adicional Minecraft não está ativo nesta loja.", 300);
            case 426:
                return parar("Atualize o plugin NeverMiss (mínima " + texto(r.json.get("minima")) + ").", 600);
            case 429:
                return Math.max(5, Math.min(300, inteiro(r.retryAfter, 5)));
            default:
                return foraDoAr("A NeverMiss respondeu " + r.status);
        }
    }

    private int parar(String motivo, int segundos) {
        situacao = motivo;
        jogo.aviso(motivo);
        return segundos;
    }

    private int foraDoAr(String motivo) {
        situacao = motivo;
        if (falhasSeguidas == 0) jogo.aviso(motivo + ". Tento de novo em 30 s.");
        falhasSeguidas++;
        return Math.min(300, 30 << Math.min(4, falhasSeguidas - 1));
    }

    private JsonObject corpoFila(List<String> online) {
        JsonObject servidor = new JsonObject();
        String v = jogo.versao();
        servidor.addProperty("versao", v.length() > 80 ? v.substring(0, 80) : v);
        servidor.addProperty("onlineMode", jogo.onlineMode());
        servidor.addProperty("plataforma", jogo.plataforma());
        servidor.addProperty("edicao", jogo.edicao());
        Bedrock b = jogo.bedrock();
        if (b == null) {
            servidor.add("bedrock", JsonNull.INSTANCE);
        } else {
            JsonObject o = new JsonObject();
            o.addProperty("floodgate", true);
            o.addProperty("prefixo", b.prefixo);
            servidor.add("bedrock", o);
        }
        JsonArray nicks = new JsonArray();
        for (String n : online) nicks.add(new JsonPrimitive(n));
        JsonArray recursos = new JsonArray();
        if (jogo.versaoMc() != null) recursos.add(new JsonPrimitive("kit"));
        JsonObject c = new JsonObject();
        c.addProperty("versao", versao);
        c.add("recursos", recursos);
        c.add("servidor", servidor);
        c.add("online", nicks);
        return c;
    }

    private List<Entrega> lerEntregas(JsonElement el) {
        List<Entrega> lista = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return lista;
        for (JsonElement item : el.getAsJsonArray()) {
            if (!item.isJsonObject()) continue;
            JsonObject o = item.getAsJsonObject();
            String id = texto(o.get("id"));
            if (id == null || !ID.matcher(id).matches()) {
                jogo.aviso("Entrega com id inválido ignorada");
                continue;
            }
            Entrega e = new Entrega(id);
            e.nick = texto(o.get("nick"));
            e.soOnline = booleano(o.get("soOnline"));
            e.teste = "teste".equals(texto(o.get("evento")));
            e.bedrock = "bedrock".equals(texto(o.get("edicaoJogador")));
            JsonElement cs = o.get("comandos");
            if (cs != null && cs.isJsonArray()) {
                for (JsonElement c : cs.getAsJsonArray()) e.comandos.add(c.isJsonPrimitive() ? c.getAsString() : null);
            }
            JsonElement as = o.get("acoes");
            if (as != null && as.isJsonArray()) for (JsonElement a : as.getAsJsonArray()) lerAcao(e, a);
            lista.add(e);
        }
        return lista;
    }

    /** A ação "kit" (contrato, 17.3). A que este plugin não conhece faz a entrega falhar, sem rodar nada. */
    private static void lerAcao(Entrega e, JsonElement a) {
        JsonObject o = a.isJsonObject() ? a.getAsJsonObject() : null;
        JsonElement k = o == null ? null : o.get("kit");
        Kit kit = e.kit == null && "kit".equals(texto(o == null ? null : o.get("tipo"))) && k != null && k.isJsonObject()
                ? Kit.ler(k.getAsJsonObject()) : null;
        if (kit == null) {
            e.acaoDesconhecida = true;
            return;
        }
        e.kit = kit;
        e.darAgora = booleano(o.get("darAgora"));
        e.vezes = Math.max(1, Math.min(1000, inteiro(o.get("vezes"), 1)));
        JsonElement r = o.get("resgate");
        if (r != null && r.isJsonObject()) e.intervaloMs = Math.max(1, inteiro(r.getAsJsonObject().get("intervaloS"), 1)) * 1000L;
    }

    Resposta post(String caminho, JsonObject corpo) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(api + caminho).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + chave);
        c.setRequestProperty("User-Agent", "NeverMissMC/" + versao);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(corpo.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        JsonObject json = new JsonObject();
        if (in != null) {
            try (InputStream fecha = in) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = fecha.read(b)) > 0; ) buf.write(b, 0, n);
                JsonElement lido = JsonParser.parseString(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                if (lido.isJsonObject()) json = lido.getAsJsonObject();
            } catch (RuntimeException naoEJson) {
                // corpo que não é JSON: fica só o status
            }
        }
        return new Resposta(status, json, c.getHeaderField("Retry-After"));
    }

    /** Fora da thread principal. null = nick que não serve para buscar. */
    private UUID uuidDe(Entrega e) throws Exception {
        if (!e.bedrock) return NICK.matcher(e.nick).matches() ? jogo.uuid(e.nick) : null;
        String nome = nomeBedrock(e.nick);
        if (nome == null) return null;
        if (jogo.online(nome)) return jogo.uuid(nome);
        // O Floodgate cola o gamertag no endereço da GeyserMC sem codificar: com espaço a busca seria de outro nome.
        if (e.nick.indexOf(' ') >= 0) throw new IOException("com espaço, o {uuid} do Bedrock só sai com o jogador online");
        return jogo.bedrock().uuid(e.nick);
    }

    /** O UUID da conta original, pela Mojang. Para os proxies, que não guardam quem já entrou. null = não existe. */
    static UUID uuidMojang(String nick) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.mojang.com/users/profiles/minecraft/" + nick).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "NeverMissMC");
            int status = c.getResponseCode();
            if (status == 204 || status == 404) return null;
            if (status != 200) throw new IOException("a Mojang respondeu " + status);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                String id = texto(JsonParser.parseString(new String(buf.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject().get("id"));
                if (id == null || !id.matches("[0-9a-fA-F]{32}")) throw new IOException("resposta da Mojang sem id");
                return UUID.fromString(id.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String texto(JsonElement el) {
        return el == null || !el.isJsonPrimitive() ? null : el.getAsString();
    }

    static boolean booleano(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean();
    }

    static int inteiro(JsonElement el, int padrao) {
        return inteiro(texto(el), padrao);
    }

    private static int inteiro(String s, int padrao) {
        try {
            return s == null ? padrao : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return padrao;
        }
    }

    private static List<String> textos(JsonElement el) {
        List<String> l = new ArrayList<>();
        if (el != null && el.isJsonArray()) {
            for (JsonElement x : el.getAsJsonArray()) {
                if (l.size() < MAX_ESPERANDO && x.isJsonPrimitive()) l.add(x.getAsString());
            }
        }
        return Collections.unmodifiableList(l);
    }

    static final class Entrega {
        final String id;
        final List<String> comandos = new ArrayList<>();
        String nick;
        boolean soOnline;
        boolean teste;
        // edicaoJogador "bedrock": o nick é o gamertag do Xbox
        boolean bedrock;
        UUID uuid;
        // A busca do UUID deu erro (não é o mesmo que "não existe").
        boolean uuidFalhou;
        // A ação "kit": o kit, se dá agora na compra, quantas vezes e o intervalo do /kit (0 = sem resgate)
        Kit kit;
        boolean darAgora;
        int vezes = 1;
        long intervaloMs;
        boolean acaoDesconhecida;

        Entrega(String id) {
            this.id = id;
        }

        boolean precisaUuid() {
            for (String c : comandos) if (c != null && c.contains("{uuid}")) return true;
            return false;
        }
    }

    static final class Resposta {
        final int status;
        final JsonObject json;
        final String retryAfter;

        Resposta(int status, JsonObject json, String retryAfter) {
            this.status = status;
            this.json = json;
            this.retryAfter = retryAfter;
        }
    }
}
