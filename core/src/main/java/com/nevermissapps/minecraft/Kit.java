package com.nevermissapps.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O Kit da NeverMiss (contrato, seção 17): os itens que o lojista montou no painel.
 * O core resolve cada item para a versão do servidor e monta o comando vanilla que dá ele (give, e o
 * item replace/replaceitem para pôr num espaço). A plataforma só roda (ver {@link Jogo#darPeca}).
 */
final class Kit {
    static final Pattern CHAVE = Pattern.compile("^[a-z0-9-]{1,24}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    // Tudo que entra num comando passa por um formato fechado: nada vira outro argumento.
    private static final Pattern ITEM = Pattern.compile("^[a-z0-9_]{1,64}$");
    private static final Pattern LEGADO = Pattern.compile("^([A-Z0-9_]{1,40})(?::(\\d{1,2}))?$");
    private static final Pattern ENCANTAMENTO = Pattern.compile("^[a-z_]{1,40}$");
    private static final Pattern ENCANTAMENTO_LEGADO = Pattern.compile("^[A-Z_]{1,40}$");
    private static final Pattern CONTROLE = Pattern.compile("\\p{Cc}");
    private static final Pattern COR = Pattern.compile("&([0-9a-fk-orxA-FK-ORX])");
    private static final Pattern VERSAO = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final int MAX_ITENS = 41;
    private static final String[] ARMADURA = {"feet", "legs", "chest", "head"};

    // O nome do Bukkit (o "legado" do catálogo, 1.7.10 a 1.12) que não é o id vanilla da mesma época.
    // Os outros são o próprio nome em minúsculas (DIAMOND_SWORD = diamond_sword).
    private static final Map<String, String> VANILLA_ANTIGO = new HashMap<>();
    // O número de cada encantamento antes do 1.13 (o NBT do give era "ench:[{id:16s,lvl:5s}]").
    private static final Map<String, Integer> NUMERO_ANTIGO = new HashMap<>();

    static {
        String[] nomes = {
                "WOOD_SWORD wooden_sword", "WOOD_PICKAXE wooden_pickaxe", "WOOD_AXE wooden_axe", "WOOD_SPADE wooden_shovel",
                "WOOD_HOE wooden_hoe", "STONE_SPADE stone_shovel", "IRON_SPADE iron_shovel", "DIAMOND_SPADE diamond_shovel",
                "GOLD_SWORD golden_sword", "GOLD_PICKAXE golden_pickaxe", "GOLD_AXE golden_axe", "GOLD_SPADE golden_shovel",
                "GOLD_HOE golden_hoe", "GOLD_HELMET golden_helmet", "GOLD_CHESTPLATE golden_chestplate",
                "GOLD_LEGGINGS golden_leggings", "GOLD_BOOTS golden_boots", "GRILLED_PORK cooked_porkchop", "PORK porkchop",
                "RAW_BEEF beef", "RAW_CHICKEN chicken", "CARROT_ITEM carrot", "POTATO_ITEM potato", "MUSHROOM_SOUP mushroom_stew",
                "TOTEM totem_of_undying", "EXP_BOTTLE experience_bottle", "EYE_OF_ENDER ender_eye", "NETHER_STALK nether_wart",
                "BREWING_STAND_ITEM brewing_stand", "ENCHANTMENT_TABLE enchanting_table", "INK_SACK dye", "WATCH clock",
                "EMPTY_MAP map", "LEASH lead", "THIN_GLASS glass_pane", "SMOOTH_BRICK stonebrick", "BRICK brick_block",
                "ENDER_STONE end_stone", "HARD_CLAY hardened_clay", "WORKBENCH crafting_table", "IRON_FENCE iron_bars",
                "REDSTONE_TORCH_ON redstone_torch", "DIODE repeater", "REDSTONE_COMPARATOR comparator", "PISTON_BASE piston",
                "PISTON_STICKY_BASE sticky_piston", "RAILS rail", "FIREWORK fireworks", "SULPHUR gunpowder", "SNOW_BALL snowball",
                "SEEDS wheat_seeds", "WOOD_DOOR wooden_door", "LOG_2 log2", "WOOD planks", "STAINED_CLAY stained_hardened_clay",
                "SLIME_BLOCK slime", "SNOW_BLOCK snow", "SUGAR_CANE reeds"};
        for (String n : nomes) VANILLA_ANTIGO.put(n.substring(0, n.indexOf(' ')), n.substring(n.indexOf(' ') + 1));
        String[] numeros = {
                "PROTECTION_ENVIRONMENTAL 0", "PROTECTION_FIRE 1", "PROTECTION_FALL 2", "PROTECTION_EXPLOSIONS 3",
                "PROTECTION_PROJECTILE 4", "OXYGEN 5", "WATER_WORKER 6", "THORNS 7", "DEPTH_STRIDER 8", "FROST_WALKER 9",
                "BINDING_CURSE 10", "DAMAGE_ALL 16", "DAMAGE_UNDEAD 17", "DAMAGE_ARTHROPODS 18", "KNOCKBACK 19", "FIRE_ASPECT 20",
                "LOOT_BONUS_MOBS 21", "SWEEPING_EDGE 22", "DIG_SPEED 32", "SILK_TOUCH 33", "DURABILITY 34",
                "LOOT_BONUS_BLOCKS 35", "ARROW_DAMAGE 48", "ARROW_KNOCKBACK 49", "ARROW_FIRE 50", "ARROW_INFINITE 51",
                "LUCK 61", "LURE 62", "MENDING 70", "VANISHING_CURSE 71"};
        for (String n : numeros) NUMERO_ANTIGO.put(n.substring(0, n.indexOf(' ')), Integer.parseInt(n.substring(n.indexOf(' ') + 1)));
    }

    final String id;
    final String nome;
    final String chave;
    final boolean equipar;
    final List<Item> itens;

    private Kit(String id, String nome, String chave, boolean equipar, List<Item> itens) {
        this.id = id;
        this.nome = nome;
        this.chave = chave;
        this.equipar = equipar;
        this.itens = itens;
    }

    /** O item como veio da API: o id moderno (1.13+) e o nome do Bukkit de 1.7.10 a 1.12 (null = não existe antes do 1.13). */
    static final class Item {
        final int espaco;
        final String item;
        final String legado;
        final int dano;
        final int qtd;
        // {id moderno, nome do Bukkit antigo (ou null), nível}
        final List<String[]> encantamentos;
        final String nome;
        final List<String> descricao;

        Item(int espaco, String item, String legado, int dano, int qtd, List<String[]> encantamentos, String nome, List<String> descricao) {
            this.espaco = espaco;
            this.item = item;
            this.legado = legado;
            this.dano = dano;
            this.qtd = qtd;
            this.encantamentos = encantamentos;
            this.nome = nome;
            this.descricao = descricao;
        }
    }

    /** O item resolvido para a versão do servidor: o id vanilla dela e os encantamentos que existem nela. */
    static final class Peca {
        final String id;
        // O "data" do 1.7.10 a 1.12 (a lã vermelha é wool com 14). 0 no 1.13+.
        final int dano;
        final int qtd;
        // id vanilla (1.13+) ou o número (antes) → nível
        final Map<String, Integer> encantamentos;
        // Com § no lugar do & (as cores)
        final String nome;
        final List<String> descricao;

        Peca(String id, int dano, int qtd, Map<String, Integer> encantamentos, String nome, List<String> descricao) {
            this.id = id;
            this.dano = dano;
            this.qtd = qtd;
            this.encantamentos = encantamentos;
            this.nome = nome;
            this.descricao = descricao;
        }
    }

    /** null = kit fora do formato. Item fora do formato é pulado (como o que não existe na versão). */
    static Kit ler(JsonObject o) {
        String id = Nucleo.texto(o.get("id"));
        String chave = Nucleo.texto(o.get("chave"));
        if (id == null || !ID.matcher(id).matches() || chave == null || !CHAVE.matcher(chave).matches()) return null;
        String nome = limpo(Nucleo.texto(o.get("nome")), 32);
        List<Item> itens = new ArrayList<>();
        JsonElement lista = o.get("itens");
        if (lista != null && lista.isJsonArray()) {
            for (JsonElement el : lista.getAsJsonArray()) {
                Item i = el.isJsonObject() && itens.size() < MAX_ITENS ? item(el.getAsJsonObject()) : null;
                if (i != null) itens.add(i);
            }
        }
        JsonElement equipar = o.get("equipar");
        return new Kit(id, nome == null ? chave : nome, chave, equipar == null || !equipar.isJsonPrimitive() || equipar.getAsBoolean(),
                Collections.unmodifiableList(itens));
    }

    private static Item item(JsonObject o) {
        int espaco = Nucleo.inteiro(o.get("espaco"), -1);
        String item = Nucleo.texto(o.get("item"));
        if (espaco < 0 || espaco > 40 || item == null || !ITEM.matcher(item).matches()) return null;
        String legado = Nucleo.texto(o.get("itemLegado"));
        Matcher m = legado == null ? null : LEGADO.matcher(legado);
        boolean temLegado = m != null && m.matches();
        List<String[]> encs = new ArrayList<>();
        JsonElement es = o.get("encantamentos");
        if (es != null && es.isJsonArray()) {
            for (JsonElement el : es.getAsJsonArray()) {
                if (!el.isJsonObject() || encs.size() == 12) continue;
                JsonObject e = el.getAsJsonObject();
                String eid = Nucleo.texto(e.get("id"));
                String antigo = Nucleo.texto(e.get("idLegado"));
                if (eid == null || !ENCANTAMENTO.matcher(eid).matches()) continue;
                if (antigo != null && !ENCANTAMENTO_LEGADO.matcher(antigo).matches()) antigo = null;
                encs.add(new String[]{eid, antigo, String.valueOf(Math.max(1, Math.min(255, Nucleo.inteiro(e.get("nivel"), 1))))});
            }
        }
        List<String> descricao = new ArrayList<>();
        String d = Nucleo.texto(o.get("descricao"));
        if (d != null) {
            for (String linha : d.split("\n", 6)) {
                String l = limpo(linha, 256);
                if (l != null) descricao.add(cor(l));
            }
        }
        String nome = limpo(Nucleo.texto(o.get("nome")), 64);
        return new Item(espaco, item, temLegado ? m.group(1) : null, temLegado && m.group(2) != null ? Integer.parseInt(m.group(2)) : 0,
                Math.max(1, Math.min(64, Nucleo.inteiro(o.get("qtd"), 1))), encs, nome == null ? null : cor(nome),
                Collections.unmodifiableList(descricao));
    }

    private static String limpo(String texto, int max) {
        if (texto == null) return null;
        String t = CONTROLE.matcher(texto).replaceAll("").trim();
        if (t.length() > max) t = t.substring(0, max);
        return t.isEmpty() ? null : t;
    }

    /** O & das cores vira §, como o ChatColor.translateAlternateColorCodes do Bukkit. */
    static String cor(String texto) {
        Matcher m = COR.matcher(texto);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, "§" + m.group(1).toLowerCase(Locale.ROOT));
        m.appendTail(sb);
        return sb.toString();
    }

    /** "1.20.4-R0.1-SNAPSHOT" → {1, 20, 4}; "26.3" → {26, 3, 0}. null se não começa com uma versão. */
    static int[] versao(String texto) {
        Matcher m = texto == null ? null : VERSAO.matcher(texto);
        if (m == null || !m.find()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
    }

    static boolean desde(int[] v, int a, int b, int c) {
        if (v[0] != a) return v[0] > a;
        if (v[1] != b) return v[1] > b;
        return v[2] >= c;
    }

    /** O id vanilla do 1.7.10 a 1.12 para o nome do Bukkit ("GOLD_SWORD" → "golden_sword"). */
    static String vanillaAntigo(String legado) {
        String v = VANILLA_ANTIGO.get(legado);
        return v != null ? v : legado.toLowerCase(Locale.ROOT);
    }

    /** null = o item não existe nesta versão (nem pelo nome moderno, nem pelo antigo). */
    static Peca peca(Jogo jogo, Item it) {
        int[] v = jogo.versaoMc();
        boolean moderno = desde(v, 1, 13, 0);
        String id = null;
        if (moderno && jogo.existe(it.item)) id = it.item;
        // O nome antigo serve quando o moderno é mais novo que o servidor (o oak_sign é do 1.14: no 1.13 é sign).
        else if (it.legado != null && jogo.existe(vanillaAntigo(it.legado))) id = vanillaAntigo(it.legado);
        if (id == null) return null;
        Map<String, Integer> encs = new LinkedHashMap<>();
        for (String[] e : it.encantamentos) {
            int nivel = Integer.parseInt(e[2]);
            if (moderno) {
                String eid = !desde(v, 1, 20, 5) && e[0].equals("sweeping_edge") ? "sweeping" : e[0];
                if (jogo.existeEncantamento(eid)) encs.put(eid, nivel);
            } else if (e[1] != null && NUMERO_ANTIGO.containsKey(e[1])) {
                // Antes do 1.13 o jogo ignora o número que não conhece: não precisa conferir se existe
                encs.put(String.valueOf(NUMERO_ANTIGO.get(e[1])), nivel);
            }
        }
        return new Peca(id, moderno ? 0 : it.dano, it.qtd, encs, it.nome, it.descricao);
    }

    boolean algumExiste(Jogo jogo) {
        for (Item it : itens) if (peca(jogo, it) != null) return true;
        return false;
    }

    /**
     * Onde o item vai: o espaço dele, se está vazio (a armadura só com "equipar"; a mão secundária só do 1.9 em diante),
     * ou -1 = no inventário, onde couber (o give joga o resto no chão). Thread principal.
     */
    static int alvo(Jogo jogo, String nome, int espaco, boolean equipar) {
        if (espaco >= 36 && espaco <= 39 && !equipar) return -1;
        if (espaco == 40 && !desde(jogo.versaoMc(), 1, 9, 0)) return -1;
        return jogo.vazio(nome, espaco) ? espaco : -1;
    }

    /**
     * O comando vanilla que dá a peça ao jogador. espaco -1 = give (onde couber, e o resto cai no chão).
     * 1.20.5+: componentes; 1.13 a 1.20.4: NBT; antes: "give <nick> <item> <qtd> <data> <nbt>". O 1.7.10 não tem o replaceitem.
     */
    static String comando(int[] v, String nome, Peca p, int espaco) {
        String slot = espaco < 0 || !desde(v, 1, 8, 0) ? null : slot(v, espaco);
        if (desde(v, 1, 13, 0)) {
            String item = "minecraft:" + p.id + (desde(v, 1, 20, 5) ? componentes(v, p) : nbt(v, p)) + " " + p.qtd;
            if (slot == null) return "give " + nome + " " + item;
            return (desde(v, 1, 17, 0) ? "item replace entity " + nome + " " + slot + " with " : "replaceitem entity " + nome + " " + slot + " ") + item;
        }
        String nbt = nbtAntigo(p);
        String item = "minecraft:" + p.id + " " + p.qtd + " " + p.dano + (nbt.isEmpty() ? "" : " " + nbt);
        return slot == null ? "give " + nome + " " + item : "replaceitem entity " + nome + " " + slot + " " + item;
    }

    private static String slot(int[] v, int espaco) {
        String s = espaco < 9 ? "hotbar." + espaco
                : espaco < 36 ? "inventory." + (espaco - 9)
                : espaco == 40 ? "weapon.offhand"
                : "armor." + ARMADURA[espaco - 36];
        return desde(v, 1, 13, 0) ? s : "slot." + s;
    }

    private static String componentes(int[] v, Peca p) {
        // 1.21.5: o encantamento perdeu o "levels" e o texto deixou de ser JSON dentro de uma string.
        boolean novo = desde(v, 1, 21, 5);
        List<String> c = new ArrayList<>();
        if (!p.encantamentos.isEmpty()) {
            List<String> e = new ArrayList<>();
            for (Map.Entry<String, Integer> x : p.encantamentos.entrySet()) e.add("\"minecraft:" + x.getKey() + "\":" + x.getValue());
            String mapa = "{" + String.join(",", e) + "}";
            c.add("minecraft:enchantments=" + (novo ? mapa : "{levels:" + mapa + "}"));
        }
        if (p.nome != null) c.add("minecraft:custom_name=" + snbt(novo ? p.nome : json(p.nome)));
        if (!p.descricao.isEmpty()) {
            List<String> l = new ArrayList<>();
            for (String linha : p.descricao) l.add(snbt(novo ? linha : json(linha)));
            c.add("minecraft:lore=[" + String.join(",", l) + "]");
        }
        return c.isEmpty() ? "" : "[" + String.join(",", c) + "]";
    }

    private static String nbt(int[] v, Peca p) {
        List<String> t = new ArrayList<>();
        if (!p.encantamentos.isEmpty()) {
            List<String> e = new ArrayList<>();
            for (Map.Entry<String, Integer> x : p.encantamentos.entrySet()) e.add("{id:\"minecraft:" + x.getKey() + "\",lvl:" + x.getValue() + "s}");
            t.add("Enchantments:[" + String.join(",", e) + "]");
        }
        List<String> d = new ArrayList<>();
        if (p.nome != null) d.add("Name:" + snbt(json(p.nome)));
        if (!p.descricao.isEmpty()) {
            // A descrição virou JSON no 1.14; no 1.13 ainda era texto puro.
            List<String> l = new ArrayList<>();
            for (String linha : p.descricao) l.add(snbt(desde(v, 1, 14, 0) ? json(linha) : linha));
            d.add("Lore:[" + String.join(",", l) + "]");
        }
        if (!d.isEmpty()) t.add("display:{" + String.join(",", d) + "}");
        return t.isEmpty() ? "" : "{" + String.join(",", t) + "}";
    }

    private static String nbtAntigo(Peca p) {
        List<String> t = new ArrayList<>();
        if (!p.encantamentos.isEmpty()) {
            List<String> e = new ArrayList<>();
            for (Map.Entry<String, Integer> x : p.encantamentos.entrySet()) e.add("{id:" + x.getKey() + "s,lvl:" + x.getValue() + "s}");
            t.add("ench:[" + String.join(",", e) + "]");
        }
        List<String> d = new ArrayList<>();
        if (p.nome != null) d.add("Name:" + snbt(p.nome));
        if (!p.descricao.isEmpty()) {
            List<String> l = new ArrayList<>();
            for (String linha : p.descricao) l.add(snbt(linha));
            d.add("Lore:[" + String.join(",", l) + "]");
        }
        if (!d.isEmpty()) t.add("display:{" + String.join(",", d) + "}");
        return t.isEmpty() ? "" : "{" + String.join(",", t) + "}";
    }

    /** O texto como string JSON (o nome e a descrição do 1.14 ao 1.21.4 são JSON dentro do NBT). */
    private static String json(String texto) {
        return new JsonPrimitive(texto).toString();
    }

    /** Entre aspas, com a \ e a " escapadas: o texto do lojista não sai da string. */
    private static String snbt(String texto) {
        return "\"" + texto.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
