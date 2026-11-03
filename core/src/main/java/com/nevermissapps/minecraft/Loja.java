package com.nevermissapps.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A /loja do jogo (contrato, seção 18): o que entrega neste servidor, o Pix de uma compra e a consulta do pagamento.
 * O produto é o que o lojista criou no painel; o pagamento e a entrega são da NeverMiss (a entrega chega pela fila de
 * sempre). Tudo aqui fala com a API: nunca na thread principal.
 */
final class Loja {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    private static final Pattern NUMERO = Pattern.compile("^[A-Z0-9]{6}$");
    private static final Locale BR = new Locale("pt", "BR");
    private static final long GUARDA_MS = 15_000;
    static final String FORA = "A loja não respondeu. Tente de novo em instantes.";

    private final Nucleo nucleo;
    private volatile Catalogo guardado;
    private volatile long guardadoEm;

    Loja(Nucleo nucleo) {
        this.nucleo = nucleo;
    }

    static final class Variacao {
        final String id;
        final String nome;
        final List<String> descricao;
        final double preco;
        final double precoDe;
        final int min;
        final int max;
        final boolean esgotado;
        final int restam;
        final boolean gratis;
        final boolean bedrock;
        final Kit.Item icone;
        // O desconto por quantidade do produto: pct% a partir de N unidades (0 = sem)
        final int descontoAPartirDe;
        final double descontoPct;

        Variacao(JsonObject o) {
            id = Nucleo.texto(o.get("id"));
            nome = limpo(Nucleo.texto(o.get("nome")), 60, id);
            descricao = linhas(Nucleo.texto(o.get("descricao")));
            preco = numero(o.get("preco"));
            precoDe = numero(o.get("precoDe"));
            min = Math.max(1, Nucleo.inteiro(o.get("min"), 1));
            max = Math.max(min, Math.min(100, Nucleo.inteiro(o.get("max"), min)));
            esgotado = Nucleo.booleano(o.get("esgotado"));
            restam = Nucleo.inteiro(o.get("restam"), -1);
            gratis = Nucleo.booleano(o.get("gratis"));
            bedrock = Nucleo.booleano(o.get("bedrock"));
            icone = icone(o.get("icone"));
            JsonElement d = o.get("desconto");
            JsonObject dd = d != null && d.isJsonObject() ? d.getAsJsonObject() : new JsonObject();
            descontoAPartirDe = Math.max(0, Nucleo.inteiro(dd.get("aPartirDe"), 0));
            descontoPct = Math.min(100, numero(dd.get("pct")));
        }

        /** O total que o Pix vai cobrar (o mesmo cálculo da API: o desconto por quantidade, arredondado no centavo). */
        double total(int qtd) {
            if (gratis) return 0;
            double bruto = preco * qtd;
            if (descontoAPartirDe > 0 && descontoPct > 0 && qtd >= descontoAPartirDe) bruto -= bruto * (descontoPct / 100);
            return Math.round(bruto * 100) / 100.0;
        }
    }

    static final class Produto {
        final String id;
        final String nome;
        final List<String> descricao;
        final Kit.Item icone;
        final List<Variacao> variacoes;

        Produto(String id, String nome, List<String> descricao, Kit.Item icone, List<Variacao> variacoes) {
            this.id = id;
            this.nome = nome;
            this.descricao = descricao;
            this.icone = icone;
            this.variacoes = variacoes;
        }

        boolean esgotado() {
            for (Variacao v : variacoes) if (!v.esgotado) return false;
            return true;
        }

        /** O menor preço do que está à venda (o "a partir de" quando há mais de um). */
        double menor() {
            double m = Double.MAX_VALUE;
            for (Variacao v : variacoes) if (!v.esgotado || esgotado()) m = Math.min(m, v.preco);
            return m;
        }

        boolean aPartirDe() {
            for (Variacao v : variacoes) if (v.preco != variacoes.get(0).preco) return true;
            return false;
        }
    }

    static final class Catalogo {
        final boolean aberta;
        final String motivo;
        final String nome;
        final List<Produto> produtos;

        Catalogo(boolean aberta, String motivo, String nome, List<Produto> produtos) {
            this.aberta = aberta;
            this.motivo = motivo;
            this.nome = nome;
            this.produtos = produtos;
        }
    }

    /** O pedido como a API devolve. status: aguardando, pago, vencido ou cancelado. */
    static final class Pedido {
        final String numero;
        final String status;
        final double total;
        final boolean gratis;
        final long expiraEm;
        final String copiaCola;
        final String recebedor;
        // O QR (tamanho × tamanho módulos), bit a bit em linhas. null fora do "aguardando"
        final int tamanho;
        private final byte[] modulos;

        Pedido(JsonObject o) {
            numero = Nucleo.texto(o.get("numero"));
            status = Nucleo.texto(o.get("status"));
            total = numero(o.get("total"));
            gratis = Nucleo.booleano(o.get("gratis"));
            expiraEm = momento(Nucleo.texto(o.get("expiraEm")));
            copiaCola = Nucleo.texto(o.get("copiaCola"));
            recebedor = limpo(Nucleo.texto(o.get("recebedor")), 60, null);
            JsonElement qr = o.get("qr");
            int n = 0;
            byte[] bits = null;
            if (qr != null && qr.isJsonObject()) {
                n = Nucleo.inteiro(qr.getAsJsonObject().get("tamanho"), 0);
                try {
                    bits = Base64.getDecoder().decode(String.valueOf(Nucleo.texto(qr.getAsJsonObject().get("modulos"))));
                } catch (IllegalArgumentException e) {
                    bits = null;
                }
                if (n < 21 || n > 177 || bits == null || bits.length < (n * n + 7) / 8) {
                    n = 0;
                    bits = null;
                }
            }
            tamanho = n;
            modulos = bits;
        }

        boolean modulo(int linha, int coluna) {
            int i = linha * tamanho + coluna;
            return (modulos[i >> 3] & (0x80 >> (i & 7))) != 0;
        }
    }

    /** A mensagem para o jogador (a da API, que já vem em português, ou a nossa). */
    static final class Erro extends Exception {
        Erro(String mensagem) {
            super(mensagem);
        }
    }

    /** O catálogo deste servidor, guardado 15 s (o /loja de muita gente junta não vira uma consulta por clique). */
    Catalogo catalogo() throws Erro {
        Catalogo c = guardado;
        if (c != null && System.currentTimeMillis() - guardadoEm < GUARDA_MS) return c;
        JsonObject j = pedir("/v1/minecraft/loja", new JsonObject());
        List<Produto> produtos = new ArrayList<>();
        JsonElement ps = j.get("produtos");
        if (ps != null && ps.isJsonArray()) {
            for (JsonElement el : ps.getAsJsonArray()) {
                if (!el.isJsonObject() || produtos.size() >= 270) continue;
                JsonObject p = el.getAsJsonObject();
                String id = Nucleo.texto(p.get("id"));
                if (id == null || !ID.matcher(id).matches()) continue;
                List<Variacao> vs = new ArrayList<>();
                JsonElement lista = p.get("variacoes");
                if (lista != null && lista.isJsonArray()) {
                    for (JsonElement v : lista.getAsJsonArray()) {
                        if (!v.isJsonObject() || vs.size() >= 45) continue;
                        Variacao x = new Variacao(v.getAsJsonObject());
                        if (x.id != null && ID.matcher(x.id).matches()) vs.add(x);
                    }
                }
                if (vs.isEmpty()) continue;
                produtos.add(new Produto(id, limpo(Nucleo.texto(p.get("nome")), 60, id), linhas(Nucleo.texto(p.get("descricao"))),
                        icone(p.get("icone")), Collections.unmodifiableList(vs)));
            }
        }
        c = new Catalogo(!j.has("aberta") || Nucleo.booleano(j.get("aberta")), limpo(Nucleo.texto(j.get("motivo")), 200, null),
                limpo(Nucleo.texto(j.get("nome")), 40, null), Collections.unmodifiableList(produtos));
        guardado = c;
        guardadoEm = System.currentTimeMillis();
        return c;
    }

    /** Compra uma variação: o Pix (ou o grátis, já pago). chave: o mesmo clique repetido devolve o mesmo pedido. */
    Pedido comprar(Produto p, Variacao v, int qtd, String nick, boolean bedrock, String chave) throws Erro {
        JsonObject corpo = new JsonObject();
        corpo.addProperty("produto", p.id);
        corpo.addProperty("variacao", v.id);
        corpo.addProperty("qtd", qtd);
        corpo.addProperty("nick", nick);
        corpo.addProperty("edicao", bedrock ? "bedrock" : "java");
        corpo.add("chave", new JsonPrimitive(chave));
        // O estoque mudou: o próximo /loja lê de novo
        guardado = null;
        return pedido(pedir("/v1/minecraft/loja/pedidos", corpo));
    }

    Pedido status(String numero) throws Erro {
        if (numero == null || !NUMERO.matcher(numero).matches()) throw new Erro("Pedido não encontrado.");
        return pedido(pedir("/v1/minecraft/loja/pedidos/" + numero, new JsonObject()));
    }

    private static Pedido pedido(JsonObject j) throws Erro {
        Pedido p = new Pedido(j);
        if (p.numero == null || !NUMERO.matcher(p.numero).matches() || p.status == null) throw new Erro(FORA);
        if ("aguardando".equals(p.status) && p.tamanho == 0) throw new Erro(FORA);
        return p;
    }

    private JsonObject pedir(String caminho, JsonObject corpo) throws Erro {
        if (!nucleo.configurado()) throw new Erro("A loja está fechada agora.");
        Nucleo.Resposta r;
        try {
            r = nucleo.post(caminho, corpo);
        } catch (IOException e) {
            throw new Erro(FORA);
        }
        if (r.status >= 200 && r.status < 300) return r.json;
        String msg = Nucleo.texto(r.json.get("error"));
        // A chave revogada ou errada é do dono, não do jogador
        if (r.status == 401 || r.status >= 500 || msg == null) throw new Erro(r.status == 401 ? "A loja está fechada agora." : FORA);
        throw new Erro(limpo(msg, 200, FORA));
    }

    /** "R$ 1.234,56" */
    static String reais(double v) {
        return String.format(BR, "R$ %,.2f", v);
    }

    private static Kit.Item icone(JsonElement el) {
        JsonObject o = el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        return Kit.icone(Nucleo.texto(o.get("item")), Nucleo.texto(o.get("legado")));
    }

    private static double numero(JsonElement el) {
        try {
            return el == null || !el.isJsonPrimitive() ? 0 : Math.max(0, el.getAsDouble());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long momento(String iso) {
        try {
            return iso == null ? 0 : Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /** O texto numa linha, sem os § de cor do lojista nem os caracteres de controle. */
    static String limpo(String s, int max, String padrao) {
        if (s == null) return padrao;
        String t = s.replaceAll("§.?", "").replaceAll("\\p{Cc}", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return padrao;
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }

    /** A descrição quebrada em até 4 linhas de ~34 letras (o que cabe no lore sem esticar o menu). */
    static List<String> linhas(String texto) {
        List<String> saida = new ArrayList<>();
        String t = limpo(texto, 300, null);
        if (t == null) return saida;
        StringBuilder linha = new StringBuilder();
        for (String palavra : t.split(" ")) {
            if (linha.length() > 0 && linha.length() + 1 + palavra.length() > 34) {
                saida.add(linha.toString());
                linha.setLength(0);
                if (saida.size() == 4) break;
            }
            if (linha.length() > 0) linha.append(' ');
            linha.append(palavra.length() > 34 ? palavra.substring(0, 34) : palavra);
        }
        if (saida.size() < 4 && linha.length() > 0) saida.add(linha.toString());
        else if (saida.size() == 4) saida.set(3, saida.get(3) + "…");
        return saida;
    }
}
