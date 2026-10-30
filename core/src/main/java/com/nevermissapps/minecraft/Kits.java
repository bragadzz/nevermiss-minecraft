package com.nevermissapps.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * O /kit (contrato, 17.4): quem comprou um kit com resgate pega de novo a cada intervalo, até o fim do prazo.
 * Os direitos vêm da API (POST /v1/minecraft/kits); a hora do último resgate fica no {@link Resgates}.
 */
final class Kits {
    private static final long COPIA_MS = 60_000;
    private static final int MAX_NICKS = 100;

    private final Jogo jogo;
    private final Nucleo nucleo;
    final Resgates resgates;
    private final Map<String, Copia> copias = new ConcurrentHashMap<>();
    // Quem entrou e ainda não teve os kits baixados: vão juntos, para não gastar uma consulta por jogador.
    private final Set<String> pedir = new LinkedHashSet<>();
    private boolean agendado;
    LongSupplier relogio = System::currentTimeMillis;

    Kits(Jogo jogo, Nucleo nucleo, Resgates resgates) {
        this.jogo = jogo;
        this.nucleo = nucleo;
        this.resgates = resgates;
    }

    private static final class Direito {
        final String id;
        final String kit;
        final long intervaloMs;
        // 0 = para sempre
        final long ate;

        Direito(String id, String kit, long intervaloMs, long ate) {
            this.id = id;
            this.kit = kit;
            this.intervaloMs = intervaloMs;
            this.ate = ate;
        }
    }

    /** O que a API respondeu para um jogador. */
    private static final class Copia {
        final long em;
        final List<Direito> direitos;
        final Map<String, Kit> kits;
        final boolean pausado;
        // O plugin confirmou uma entrega com resgate depois desta cópia: o direito novo nasceu na API.
        volatile boolean velha;

        Copia(long em, List<Direito> direitos, Map<String, Kit> kits, boolean pausado) {
            this.em = em;
            this.direitos = direitos;
            this.kits = kits;
            this.pausado = pausado;
        }
    }

    private boolean daKit() {
        return jogo.versaoMc() != null && nucleo.configurado();
    }

    /** Um jogador entrou: os kits dele são baixados (junto com os de quem entrou nos 2 s seguintes). */
    void aoEntrar(String nick) {
        if (!daKit() || !Nucleo.NICK.matcher(nick).matches()) return;
        synchronized (pedir) {
            pedir.add(nick);
            if (agendado) return;
            agendado = true;
        }
        jogo.depois(2, this::baixarPedidos);
    }

    void aoSair(String nick) {
        copias.remove(nick.toLowerCase(Locale.ROOT));
    }

    /** O plugin confirmou uma entrega de kit com resgate para esse nick: o próximo /kit baixa de novo. */
    void sujar(String nick) {
        Copia c = copias.get(nick.toLowerCase(Locale.ROOT));
        if (c != null) c.velha = true;
    }

    private void baixarPedidos() {
        List<String> nicks = new ArrayList<>();
        synchronized (pedir) {
            for (String n : pedir) if (nicks.size() < MAX_NICKS) nicks.add(n);
            pedir.removeAll(nicks);
            agendado = !pedir.isEmpty();
        }
        if (!nicks.isEmpty()) {
            Map<String, Copia> novas = baixar(nicks);
            if (novas != null) copias.putAll(novas);
        }
        if (agendado) jogo.depois(2, this::baixarPedidos);
    }

    /** Fora da thread principal. null = a NeverMiss não respondeu. */
    private Map<String, Copia> baixar(List<String> nicks) {
        JsonArray lista = new JsonArray();
        for (String n : nicks) lista.add(new JsonPrimitive(n));
        JsonObject corpo = new JsonObject();
        corpo.add("nicks", lista);
        Nucleo.Resposta r;
        try {
            r = nucleo.post("/v1/minecraft/kits", corpo);
        } catch (IOException e) {
            return null;
        }
        if (r.status != 200) return null;
        long agora = relogio.getAsLong();
        Map<String, Kit> kits = new HashMap<>();
        JsonElement ks = r.json.get("kits");
        if (ks != null && ks.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : ks.getAsJsonObject().entrySet()) {
                Kit k = e.getValue().isJsonObject() ? Kit.ler(e.getValue().getAsJsonObject()) : null;
                if (k != null) kits.put(e.getKey(), k);
            }
        }
        Map<String, List<Direito>> porNick = new HashMap<>();
        for (String n : nicks) porNick.put(n.toLowerCase(Locale.ROOT), new ArrayList<>());
        JsonElement ds = r.json.get("direitos");
        if (ds != null && ds.isJsonArray()) {
            for (JsonElement el : ds.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject d = el.getAsJsonObject();
                String id = Nucleo.texto(d.get("id"));
                String nick = Nucleo.texto(d.get("nick"));
                String kit = Nucleo.texto(d.get("kit"));
                List<Direito> dele = nick == null ? null : porNick.get(nick.toLowerCase(Locale.ROOT));
                if (id == null || kit == null || dele == null || !kits.containsKey(kit)) continue;
                long intervalo = Math.max(1, Nucleo.inteiro(d.get("intervaloS"), 1)) * 1000L;
                dele.add(new Direito(id, kit, intervalo, momento(Nucleo.texto(d.get("ate")))));
            }
        }
        boolean pausado = Nucleo.booleano(r.json.get("pausado"));
        Map<String, Copia> novas = new HashMap<>();
        for (Map.Entry<String, List<Direito>> e : porNick.entrySet()) {
            novas.put(e.getKey(), new Copia(agora, Collections.unmodifiableList(e.getValue()), kits, pausado));
        }
        return novas;
    }

    /** "2026-11-28T23:21:04.000Z" → epoch ms. null (para sempre) e o que não é data = 0. */
    private static long momento(String iso) {
        if (iso == null) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /** A cópia guardada (até 60 s) ou uma nova. Fora da thread principal. null = sem cópia e a NeverMiss não respondeu. */
    private Copia copia(String nome) {
        String n = nome.toLowerCase(Locale.ROOT);
        Copia c = copias.get(n);
        if (c != null && !c.velha && relogio.getAsLong() - c.em < COPIA_MS) return c;
        Map<String, Copia> novas = baixar(Collections.singletonList(nome));
        if (novas == null) return c;
        copias.putAll(novas);
        return novas.get(n);
    }

    /** /kit e /nmkit. O responder pode ser chamado de outra thread. */
    void comando(String nome, String[] args, Consumer<String> responder) {
        if (jogo.versaoMc() == null) {
            responder.accept("§cEste servidor não dá kits da NeverMiss.");
            return;
        }
        if (!Nucleo.NICK.matcher(nome).matches()) {
            responder.accept("§cO /kit da NeverMiss ainda não funciona para jogador Bedrock.");
            return;
        }
        if (!nucleo.configurado()) {
            responder.accept("§cA NeverMiss não está configurada neste servidor.");
            return;
        }
        String pedido = args.length == 0 ? null : args[0].toLowerCase(Locale.ROOT);
        jogo.depois(0, () -> {
            Copia c = copia(nome);
            String r;
            if (c == null) r = "§cNão consegui falar com a NeverMiss agora. Tente de novo em instantes.";
            else if (c.pausado) r = "§cA NeverMiss pausou as entregas. Tente mais tarde.";
            else if (pedido == null) r = lista(c);
            else {
                try {
                    r = jogo.naPrincipal(() -> resgatar(nome, c, pedido));
                } catch (Exception x) {
                    r = null;
                }
            }
            if (r != null) responder.accept(r);
        });
    }

    private long falta(Direito d, long agora) {
        long ultimo = resgates.ultimo(d.id);
        return ultimo == 0 ? 0 : Math.max(0, ultimo + d.intervaloMs - agora);
    }

    private String lista(Copia c) {
        long agora = relogio.getAsLong();
        Map<String, Long> menor = new LinkedHashMap<>();
        for (Direito d : c.direitos) {
            if (d.ate != 0 && d.ate <= agora) continue;
            String chave = c.kits.get(d.kit).chave;
            long f = falta(d, agora);
            Long antes = menor.get(chave);
            if (antes == null || f < antes) menor.put(chave, f);
        }
        if (menor.isEmpty()) return "§eVocê não tem kits da NeverMiss.";
        List<String> partes = new ArrayList<>();
        for (Map.Entry<String, Long> e : menor.entrySet()) partes.add(e.getKey() + " (" + (e.getValue() == 0 ? "agora" : "em " + tempo(e.getValue())) + ")");
        return "§aSeus kits: " + String.join(", ", partes) + ". Use /kit <nome>.";
    }

    /** Thread principal: confere o intervalo e marca o resgate na mesma volta, então dois /kit seguidos não dão dois kits. */
    private String resgatar(String nome, Copia c, String pedido) {
        long agora = relogio.getAsLong();
        Kit kit = null;
        Direito livre = null;
        long falta = Long.MAX_VALUE;
        for (Direito d : c.direitos) {
            Kit k = c.kits.get(d.kit);
            if (!k.chave.equals(pedido) || (d.ate != 0 && d.ate <= agora)) continue;
            kit = k;
            long f = falta(d, agora);
            if (f == 0) {
                livre = d;
                break;
            }
            falta = Math.min(falta, f);
        }
        if (kit == null) return "§cVocê não tem esse kit. Use /kit para ver os seus.";
        if (livre == null) return "§eEspere " + tempo(falta) + " para pegar o kit " + kit.nome + " de novo.";
        if (!jogo.online(nome)) return null;
        if (!kit.algumExiste(jogo)) return "§cNenhum item deste kit existe na versão deste servidor.";
        try {
            resgates.marcar(livre.id, agora);
        } catch (IOException e) {
            jogo.aviso("Não consegui gravar resgates.txt, o kit não saiu: " + e.getMessage());
            return "§cNão consegui guardar o resgate. Tente de novo.";
        }
        int n = dar(kit, nome, 1);
        jogo.log(nome + " pegou o kit " + kit.nome + " (/kit, " + n + " itens)");
        return "§aVocê pegou o kit " + kit.nome + ".";
    }

    /** Dá o kit "vezes" vezes, item por item. Thread principal. Devolve quantos itens deu. */
    int dar(Kit kit, String nome, int vezes) {
        List<Kit.Peca> pecas = new ArrayList<>();
        List<Kit.Item> deQuem = new ArrayList<>();
        for (Kit.Item it : kit.itens) {
            Kit.Peca p = Kit.peca(jogo, it);
            if (p == null) continue;
            pecas.add(p);
            deQuem.add(it);
        }
        int dados = 0;
        for (int v = 0; v < vezes; v++) {
            for (int i = 0; i < pecas.size(); i++) {
                try {
                    if (jogo.darPeca(nome, pecas.get(i), Kit.alvo(jogo, nome, deQuem.get(i).espaco, kit.equipar))) dados++;
                } catch (RuntimeException x) {
                    jogo.aviso("Erro ao dar um item do kit " + kit.nome + ": " + x);
                }
            }
        }
        return dados;
    }

    /** 200 s → "3 min 20 s"; o que sobra de um dia em horas; de uma hora, em minutos. */
    static String tempo(long ms) {
        long s = (ms + 999) / 1000;
        if (s >= 86400) return s / 86400 + " d" + (s % 86400 >= 3600 ? " " + s % 86400 / 3600 + " h" : "");
        if (s >= 3600) return s / 3600 + " h" + (s % 3600 >= 60 ? " " + s % 3600 / 60 + " min" : "");
        if (s >= 60) return s / 60 + " min" + (s % 60 > 0 ? " " + s % 60 + " s" : "");
        return s + " s";
    }
}
