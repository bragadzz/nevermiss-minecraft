package com.nevermissapps.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KitTest {
    private static final String CHAVE = "nmmc_" + new String(new char[43]).replace('\0', 'a');
    private static final int[] V1_7 = {1, 7, 10}, V1_8 = {1, 8, 8}, V1_12 = {1, 12, 2}, V1_13 = {1, 13, 2}, V1_16 = {1, 16, 5},
            V1_20_4 = {1, 20, 4}, V1_20_6 = {1, 20, 6}, V1_21 = {1, 21, 11}, V26 = {26, 3, 0};

    @Rule
    public TemporaryFolder pasta = new TemporaryFolder();

    private ApiFalsa api;
    private NucleoTest.JogoFalso jogo;
    private final long[] agora = {1_800_000_000_000L};

    @Before
    public void antes() throws IOException {
        api = new ApiFalsa(0, CHAVE);
        jogo = new NucleoTest.JogoFalso();
        jogo.versao = V1_20_4;
    }

    @After
    public void depois() {
        api.close();
    }

    private Nucleo nucleo() throws IOException {
        Nucleo n = new Nucleo(jogo, new Registro(new File(pasta.getRoot(), "executadas.txt")),
                new Resgates(new File(pasta.getRoot(), "resgates.txt")), "1.2.0");
        n.configurar(api.url(), CHAVE);
        n.kits.relogio = () -> agora[0];
        return n;
    }

    // --- o kit do teste: armadura de diamante, espada com Afiação V e 64 maçãs douradas ---

    private static JsonObject item(int espaco, String item, String legado, int qtd) {
        JsonObject o = new JsonObject();
        o.addProperty("espaco", espaco);
        o.addProperty("item", item);
        o.addProperty("itemLegado", legado);
        o.addProperty("qtd", qtd);
        o.add("encantamentos", new JsonArray());
        return o;
    }

    private static JsonObject espada() {
        JsonObject e = item(0, "diamond_sword", "DIAMOND_SWORD", 1);
        e.add("encantamentos", JsonParser.parseString("[{\"id\":\"sharpness\",\"idLegado\":\"DAMAGE_ALL\",\"nivel\":5}]"));
        e.addProperty("nome", "&bLâmina \"VIP\"");
        e.addProperty("descricao", "Só para VIP\nNão perca");
        return e;
    }

    private static JsonObject vip() {
        JsonArray itens = new JsonArray();
        itens.add(espada());
        itens.add(item(1, "golden_apple", "GOLDEN_APPLE", 64));
        itens.add(item(36, "diamond_boots", "DIAMOND_BOOTS", 1));
        itens.add(item(37, "diamond_leggings", "DIAMOND_LEGGINGS", 1));
        itens.add(item(38, "diamond_chestplate", "DIAMOND_CHESTPLATE", 1));
        itens.add(item(39, "diamond_helmet", "DIAMOND_HELMET", 1));
        JsonObject k = new JsonObject();
        k.addProperty("id", "kit1");
        k.addProperty("nome", "VIP Diamante");
        k.addProperty("chave", "vip-diamante");
        k.addProperty("equipar", true);
        k.add("itens", itens);
        return k;
    }

    private static JsonArray acao(JsonObject kit, boolean darAgora, int vezes, Integer intervaloS) {
        JsonObject a = new JsonObject();
        a.addProperty("tipo", "kit");
        a.addProperty("darAgora", darAgora);
        a.addProperty("vezes", vezes);
        a.add("kit", kit);
        if (intervaloS != null) {
            JsonObject r = new JsonObject();
            r.addProperty("intervaloS", intervaloS);
            r.add("ate", null);
            a.add("resgate", r);
        }
        JsonArray l = new JsonArray();
        l.add(a);
        return l;
    }

    private Kit.Peca peca(int[] v, JsonObject item) {
        jogo.versao = v;
        JsonObject k = vip();
        JsonArray so = new JsonArray();
        so.add(item);
        k.add("itens", so);
        return Kit.peca(jogo, Kit.ler(k).itens.get(0));
    }

    private String comando(int[] v, JsonObject item, int espaco) {
        return Kit.comando(v, "Steve", peca(v, item), espaco);
    }

    @Test
    public void montaOComandoDeCadaVersao() {
        assertEquals("give Steve minecraft:diamond_sword 1 0 {ench:[{id:16s,lvl:5s}],display:{Name:\"§bLâmina \\\"VIP\\\"\",Lore:[\"Só para VIP\",\"Não perca\"]}}",
                comando(V1_7, espada(), 0));
        assertEquals("replaceitem entity Steve slot.hotbar.0 minecraft:diamond_sword 1 0 {ench:[{id:16s,lvl:5s}],display:{Name:\"§bLâmina \\\"VIP\\\"\",Lore:[\"Só para VIP\",\"Não perca\"]}}",
                comando(V1_12, espada(), 0));
        assertEquals("replaceitem entity Steve hotbar.0 minecraft:diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:5s}],display:{Name:\"\\\"§bLâmina \\\\\\\"VIP\\\\\\\"\\\"\",Lore:[\"Só para VIP\",\"Não perca\"]}} 1",
                comando(V1_13, espada(), 0));
        String nbt = "minecraft:diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:5s}],display:{Name:\"\\\"§bLâmina \\\\\\\"VIP\\\\\\\"\\\"\",Lore:[\"\\\"Só para VIP\\\"\",\"\\\"Não perca\\\"\"]}} 1";
        assertEquals("replaceitem entity Steve hotbar.0 " + nbt, comando(V1_16, espada(), 0));
        assertEquals("item replace entity Steve hotbar.0 with " + nbt, comando(V1_20_4, espada(), 0));
        assertEquals("give Steve " + nbt, comando(V1_20_4, espada(), -1));
        assertEquals("item replace entity Steve hotbar.0 with minecraft:diamond_sword[minecraft:enchantments={levels:{\"minecraft:sharpness\":5}},"
                        + "minecraft:custom_name=\"\\\"§bLâmina \\\\\\\"VIP\\\\\\\"\\\"\",minecraft:lore=[\"\\\"Só para VIP\\\"\",\"\\\"Não perca\\\"\"]] 1",
                comando(V1_20_6, espada(), 0));
        String componentes = "minecraft:diamond_sword[minecraft:enchantments={\"minecraft:sharpness\":5},"
                + "minecraft:custom_name=\"§bLâmina \\\"VIP\\\"\",minecraft:lore=[\"Só para VIP\",\"Não perca\"]] 1";
        assertEquals("item replace entity Steve hotbar.0 with " + componentes, comando(V1_21, espada(), 0));
        assertEquals("give Steve " + componentes, comando(V26, espada(), -1));

        JsonObject capacete = item(39, "diamond_helmet", "DIAMOND_HELMET", 1);
        assertEquals("replaceitem entity Steve slot.armor.head minecraft:diamond_helmet 1 0", comando(V1_8, capacete, 39));
        assertEquals("replaceitem entity Steve armor.head minecraft:diamond_helmet 1", comando(V1_16, capacete, 39));
        assertEquals("item replace entity Steve armor.head with minecraft:diamond_helmet 1", comando(V1_21, capacete, 39));
        JsonObject escudo = item(40, "shield", "SHIELD", 1);
        assertEquals("replaceitem entity Steve slot.weapon.offhand minecraft:shield 1 0", comando(V1_12, escudo, 40));
        assertEquals("item replace entity Steve inventory.26 with minecraft:shield 1", comando(V1_21, escudo, 35));
        assertEquals("give Steve minecraft:golden_apple 64 0", comando(V1_12, item(1, "golden_apple", "GOLDEN_APPLE", 64), -1));
    }

    @Test
    public void nomeAntigoEDataAntesDo113() {
        assertEquals("give Steve minecraft:wool 1 14", comando(V1_12, item(9, "red_wool", "WOOL:14", 1), -1));
        assertEquals("give Steve minecraft:red_wool 1", comando(V1_20_4, item(9, "red_wool", "WOOL:14", 1), -1));
        assertEquals("give Steve minecraft:golden_shovel 1 0", comando(V1_12, item(9, "golden_shovel", "GOLD_SPADE", 1), -1));
        assertEquals("give Steve minecraft:golden_apple 1 1", comando(V1_8, item(9, "enchanted_golden_apple", "GOLDEN_APPLE:1", 1), -1));
        // O oak_sign é do 1.14: no 1.13 sai pelo nome antigo
        jogo.naoExiste.add("oak_sign");
        assertEquals("give Steve minecraft:sign 1", comando(V1_13, item(9, "oak_sign", "SIGN", 1), -1));
        // A maça (1.21) não existe no 1.20.4 nem tem nome antigo
        jogo.naoExiste.add("mace");
        assertNull(peca(V1_20_4, item(9, "mace", null, 1)));
        assertNull(peca(V1_12, item(9, "netherite_sword", null, 1)));
    }

    @Test
    public void encantamentoQueMudouOuNaoExiste() {
        JsonObject e = item(0, "diamond_sword", "DIAMOND_SWORD", 1);
        e.add("encantamentos", JsonParser.parseString("[{\"id\":\"sweeping_edge\",\"idLegado\":\"SWEEPING_EDGE\",\"nivel\":3},"
                + "{\"id\":\"density\",\"idLegado\":null,\"nivel\":2}]"));
        jogo.naoExiste.add("density");
        assertEquals("give Steve minecraft:diamond_sword{Enchantments:[{id:\"minecraft:sweeping\",lvl:3s}]} 1", comando(V1_20_4, e, -1));
        jogo.naoExiste.clear();
        assertEquals("give Steve minecraft:diamond_sword[minecraft:enchantments={\"minecraft:sweeping_edge\":3,\"minecraft:density\":2}] 1",
                comando(V1_21, e, -1));
        assertEquals("give Steve minecraft:diamond_sword 1 0 {ench:[{id:22s,lvl:3s}]}", comando(V1_12, e, -1));
    }

    @Test
    public void itemForaDoFormatoNaoEntraNoComando() {
        JsonObject k = vip();
        JsonArray itens = new JsonArray();
        itens.add(item(0, "diamond_sword 1 @a", "DIAMOND_SWORD", 1));
        itens.add(item(41, "stone", "STONE", 1));
        itens.add(item(2, "stone", "STONE x", 1));
        k.add("itens", itens);
        Kit kit = Kit.ler(k);
        assertEquals(1, kit.itens.size());
        assertNull(kit.itens.get(0).legado);
        k.addProperty("chave", "vip diamante");
        assertNull(Kit.ler(k));
    }

    // --- a entrega e o /kit, contra a API falsa ---

    private ApiFalsa.Entrega entregaVip(String id, String nick, boolean darAgora, int vezes, Integer intervaloS) {
        ApiFalsa.Entrega e = api.adicionar(id, nick, darAgora);
        e.acoes = acao(vip(), darAgora, vezes, intervaloS);
        return e;
    }

    private String kit(Nucleo n, String nome, String... args) {
        List<String> r = new ArrayList<>();
        int antes = jogo.agendadas.size();
        n.kits.comando(nome, args, r::add);
        while (jogo.agendadas.size() > antes) jogo.agendadas.remove(antes).run();
        return r.isEmpty() ? null : r.get(0);
    }

    @Test
    public void entregaOKitComArmaduraEquipadaEContaComoOPrimeiroResgate() throws IOException {
        entregaVip("mckit", "Steve", true, 2, 300);
        jogo.online.add("Steve");
        Nucleo n = nucleo();
        // O 1º pull só fica sabendo quem a entrega espera; o 2º manda o Steve em "online"
        n.ciclo();
        n.ciclo();
        assertEquals("entregue", api.entrega("mckit").estado);
        assertEquals("[\"kit\"]", api.pulls.get(1).get("recursos").toString());
        // 1ª vez: cada item no espaço dele (a armadura no corpo); 2ª: os espaços estão ocupados e vai tudo pelo give
        assertEquals(12, jogo.rodou.size());
        assertEquals("item replace entity Steve armor.head with minecraft:diamond_helmet 1", jogo.rodou.get(5));
        assertEquals("give Steve minecraft:golden_apple 64", jogo.rodou.get(7));
        assertTrue(jogo.rodou.get(11).startsWith("give Steve minecraft:diamond_helmet"));
        assertEquals("Steve: §aVocê recebeu o kit VIP Diamante. Use /kit vip-diamante a cada 5 min.", jogo.mensagens.get(0));
        assertEquals(Arrays.asList("mckit " + agora[0]), Files.readAllLines(new File(pasta.getRoot(), "resgates.txt").toPath(), StandardCharsets.UTF_8));

        // O kit da compra é o 1º resgate: o /kit só sai depois do intervalo
        assertEquals("§eEspere 5 min para pegar o kit VIP Diamante de novo.", kit(n, "Steve", "vip-diamante"));
        agora[0] += 100_000;
        assertEquals("§aSeus kits: vip-diamante (em 3 min 20 s). Use /kit <nome>.", kit(n, "steve"));
        agora[0] += 200_000;
        assertEquals("§aVocê pegou o kit VIP Diamante.", kit(n, "Steve", "VIP-Diamante"));
        assertEquals(18, jogo.rodou.size());
        assertEquals("§eEspere 5 min para pegar o kit VIP Diamante de novo.", kit(n, "Steve", "vip-diamante"));
        assertEquals(18, jogo.rodou.size());
    }

    @Test
    public void quemNaoComprouNaoPega() throws IOException {
        entregaVip("mckit", "Steve", true, 1, 300);
        jogo.online.add("Steve");
        jogo.online.add("Alex");
        Nucleo n = nucleo();
        n.ciclo();
        n.ciclo();
        int dados = jogo.rodou.size();
        assertEquals("§cVocê não tem esse kit. Use /kit para ver os seus.", kit(n, "Alex", "vip-diamante"));
        assertEquals("§eVocê não tem kits da NeverMiss.", kit(n, "Alex"));
        assertEquals(dados, jogo.rodou.size());
    }

    @Test
    public void soOResgateSaiSemOJogadorEOPrimeiroKitEhNaHora() throws IOException {
        entregaVip("mcresgate", "Steve", false, 1, 60);
        Nucleo n = nucleo();
        // O jogador entrou antes da compra: a cópia dele não tem o kit
        jogo.online.add("Steve");
        assertEquals("§eVocê não tem kits da NeverMiss.", kit(n, "Steve"));
        jogo.online.clear();
        n.ciclo();
        assertEquals("entregue", api.entrega("mcresgate").estado);
        assertTrue(jogo.rodou.isEmpty());
        // A confirmação deixou a cópia velha: o /kit baixa de novo e acha o direito novo
        jogo.online.add("Steve");
        assertEquals("§aVocê pegou o kit VIP Diamante.", kit(n, "Steve", "vip-diamante"));
        assertEquals(6, jogo.rodou.size());
        assertEquals(2, api.pedidosDeKits);
    }

    @Test
    public void kitEsperaOJogadorEntrar() throws IOException {
        entregaVip("mckit", "Steve", true, 1, null);
        Nucleo n = nucleo();
        n.ciclo();
        assertEquals("pendente", api.entrega("mckit").estado);
        assertTrue(jogo.rodou.isEmpty());
    }

    @Test
    public void kitSemNenhumItemNaVersaoFalhaSemRodarNada() throws IOException {
        ApiFalsa.Entrega e = entregaVip("mckit", "Steve", true, 1, null);
        e.comandos.add("say comprou");
        jogo.online.add("Steve");
        jogo.versao = V1_12;
        jogo.naoExiste.addAll(Arrays.asList("diamond_sword", "golden_apple", "diamond_boots", "diamond_leggings", "diamond_chestplate", "diamond_helmet"));
        Nucleo n = nucleo();
        n.ciclo();
        n.ciclo();
        assertEquals("falhou", api.entrega("mckit").estado);
        assertEquals("kit_incompativel", api.entrega("mckit").motivo);
        assertTrue(jogo.rodou.isEmpty());
    }

    @Test
    public void proxyNaoDaKit() throws IOException {
        jogo.versao = null;
        jogo.plataforma = "velocity";
        entregaVip("mckit", "Steve", true, 1, null);
        jogo.online.add("Steve");
        Nucleo n = nucleo();
        n.ciclo();
        n.ciclo();
        assertEquals("[]", api.pulls.get(0).get("recursos").toString());
        assertEquals("kit_incompativel", api.entrega("mckit").motivo);
        assertEquals("§cEste servidor não dá kits da NeverMiss.", kit(n, "Steve"));
        assertTrue(jogo.rodou.isEmpty());
    }

    @Test
    public void acaoDesconhecidaFalhaSemRodar() throws IOException {
        ApiFalsa.Entrega e = api.adicionar("mcacao", "Steve", false, "say oi");
        e.acoes = JsonParser.parseString("[{\"tipo\":\"pet\"}]").getAsJsonArray();
        nucleo().ciclo();
        assertEquals("erro", api.entrega("mcacao").motivo);
        assertTrue(jogo.rodou.isEmpty());
    }

    @Test
    public void tempo() {
        assertEquals("10 s", Kits.tempo(9_001));
        assertEquals("5 min", Kits.tempo(300_000));
        assertEquals("1 h 1 min", Kits.tempo(3_661_000));
        assertEquals("1 d 1 h", Kits.tempo(90_000_000));
    }
}
