package com.nevermissapps.minecraft;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * O mod contra um NeoForge 1.21.1 de mentira, com os nomes da Mojang e o Brigadier de verdade: os eventos por reflexão, o
 * /nevermiss só para quem pode o /op, a entrega com o kit (a armadura no corpo, a espada pelo give porque o espaço está
 * ocupado, o item que não existe pulado), a mensagem pelo tellraw e o /kit esperando o intervalo.
 */
public class ForgeTest {
    private static final String CHAVE = "nmmc_" + new String(new char[43]).replace('\0', 'b');

    @Rule
    public TemporaryFolder pasta = new TemporaryFolder();

    private final ExecutorService jogo = Executors.newSingleThreadExecutor();
    private final List<String> rodados = new CopyOnWriteArrayList<>();
    private final Fonte console = new Fonte(null);
    private final Jogador kiteiro = new Jogador("Kiteiro");
    private final NeoForge.Barramento bus = (NeoForge.Barramento) NeoForge.EVENT_BUS;
    private ApiFalsa api;

    // --- o jogo de mentira: os métodos com os nomes da Mojang, como no NeoForge ---

    static final class Perfil {
        private final String nome;

        Perfil(String nome) {
            this.nome = nome;
        }

        public String getName() {
            return nome;
        }
    }

    static final class Item {
        private final boolean vazio;

        Item(boolean vazio) {
            this.vazio = vazio;
        }

        public boolean isEmpty() {
            return vazio;
        }
    }

    static final class Inventario {
        final Set<Integer> ocupados = new HashSet<>();

        public Item getItem(int espaco) {
            return new Item(!ocupados.contains(espaco));
        }
    }

    static final class Jogador {
        private final Perfil perfil;
        final Inventario inventario = new Inventario();

        Jogador(String nome) {
            perfil = new Perfil(nome);
        }

        public Perfil getGameProfile() {
            return perfil;
        }

        public Inventario getInventory() {
            return inventario;
        }
    }

    static final class Fonte {
        private final Object entidade;

        Fonte(Object entidade) {
            this.entidade = entidade;
        }

        public Object getEntity() {
            return entidade;
        }
    }

    final class Servidor implements Executor {
        public Fonte createCommandSourceStack() {
            return console;
        }

        public boolean usesAuthentication() {
            return false;
        }

        public String getServerVersion() {
            return "1.21.1";
        }

        public Servidor getPlayerList() {
            return this;
        }

        public Jogador getPlayerByName(String nome) {
            return nome.equalsIgnoreCase("Kiteiro") ? kiteiro : null;
        }

        @Override
        public void execute(Runnable r) {
            jogo.execute(r);
        }
    }

    /** Lê até o espaço. Com a lista, confere o id já na leitura, como o give e o enchant do jogo. */
    private static ArgumentType<String> ate(String... validos) {
        Set<String> ok = new HashSet<>(Arrays.asList(validos));
        return r -> {
            int inicio = r.getCursor();
            while (r.canRead() && r.peek() != ' ') r.skip();
            String t = r.getString().substring(inicio, r.getCursor());
            if (!ok.isEmpty() && !ok.contains(t.replaceFirst("^minecraft:", "").replaceFirst("\\[.*$", ""))) {
                r.setCursor(inicio);
                throw new SimpleCommandExceptionType(new LiteralMessage("Unknown " + t)).createWithContext(r);
            }
            return t;
        };
    }

    private int grava(CommandContext<Object> c) {
        rodados.add(c.getInput());
        return 1;
    }

    private CommandDispatcher<Object> vanilla() {
        CommandDispatcher<Object> d = new CommandDispatcher<>();
        d.register(literal("op").requires(s -> s == console).then(argument("alvo", ate()).executes(c -> 1)));
        d.register(literal("give").then(argument("alvo", ate()).then(argument("item", ate("diamond_sword", "diamond_helmet"))
                .executes(this::grava).then(argument("qtd", IntegerArgumentType.integer()).executes(this::grava)))));
        d.register(literal("enchant").then(argument("alvo", ate()).then(argument("encantamento", ate("sharpness")).executes(this::grava))));
        d.register(literal("item").then(argument("resto", StringArgumentType.greedyString()).executes(this::grava)));
        d.register(literal("tellraw").then(argument("alvo", ate()).then(argument("texto", StringArgumentType.greedyString()).executes(this::grava))));
        return d;
    }

    private void noJogo(Runnable r) throws Exception {
        jogo.submit(r).get();
    }

    private static void esperar(String oQue, BooleanSupplier ok) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (ok.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("não aconteceu: " + oQue);
    }

    private boolean rodou(String comando) {
        return rodados.contains(comando);
    }

    @After
    public void depois() throws Exception {
        noJogo(() -> bus.disparar(new ServerStoppingEvent(null)));
        jogo.shutdownNow();
        if (api != null) api.close();
    }

    @Test
    public void entregaOKitEOKitDeNovoEsperaOIntervalo() throws Exception {
        api = new ApiFalsa(0, CHAVE);
        File dados = new File(pasta.getRoot(), "nevermiss");
        dados.mkdirs();
        Files.write(new File(dados, "config.yml").toPath(),
                ("chave: \"" + CHAVE + "\"\napi: \"" + api.url() + "\"\n").getBytes(StandardCharsets.UTF_8));
        Forge forge = new Forge("neoforge", "net.neoforged.neoforge.common.NeoForge", "net.neoforged.neoforge.event.", dados);
        CommandDispatcher<Object> d = vanilla();
        Servidor servidor = new Servidor();
        kiteiro.inventario.ocupados.add(0);
        ApiFalsa.Entrega e = api.adicionar("mckit", "Kiteiro", true);
        e.acoes = JsonParser.parseString("[{\"tipo\":\"kit\",\"darAgora\":true,\"vezes\":1,\"resgate\":{\"intervaloS\":3600,\"ate\":null},"
                + "\"kit\":{\"id\":\"kitvip\",\"nome\":\"VIP Diamante\",\"chave\":\"vip-diamante\",\"equipar\":true,\"itens\":["
                + "{\"espaco\":0,\"item\":\"diamond_sword\",\"itemLegado\":\"DIAMOND_SWORD\",\"qtd\":1,"
                + "\"encantamentos\":[{\"id\":\"sharpness\",\"idLegado\":\"DAMAGE_ALL\",\"nivel\":5}]},"
                + "{\"espaco\":39,\"item\":\"diamond_helmet\",\"itemLegado\":\"DIAMOND_HELMET\",\"qtd\":1,\"encantamentos\":[]},"
                + "{\"espaco\":2,\"item\":\"netherite_ingot\",\"qtd\":1,\"encantamentos\":[]}]}}]").getAsJsonArray();

        noJogo(() -> bus.disparar(new RegisterCommandsEvent(d)));
        noJogo(() -> bus.disparar(new ServerStartedEvent(servidor)));
        noJogo(() -> bus.disparar(new PlayerEvent.PlayerLoggedInEvent(kiteiro)));
        assertTrue(forge.online("kiteiro"));
        assertFalse(forge.rodar("comandoquenaoexiste Steve"));
        try {
            d.execute("nevermiss status", new Fonte(kiteiro));
            fail("quem não pode o /op usou o /nevermiss");
        } catch (CommandSyntaxException esperado) {
            // o comando nem aparece para ele
        }
        noJogo(() -> {
            try {
                d.execute("nevermiss testar", console);
            } catch (CommandSyntaxException x) {
                throw new IllegalStateException(x);
            }
        });

        esperar("a entrega do kit", () -> "entregue".equals(api.entregas.get("mckit").estado));
        assertTrue(rodados.toString(), rodou("item replace entity Kiteiro armor.head with minecraft:diamond_helmet 1"));
        assertTrue(rodados.toString(), rodou("give Kiteiro minecraft:diamond_sword[minecraft:enchantments={levels:{\"minecraft:sharpness\":5}}] 1"));
        assertTrue(rodados.toString(), rodou("tellraw Kiteiro \"§aVocê recebeu o kit VIP Diamante. Use /kit vip-diamante a cada 1 h.\""));
        for (String r : rodados) assertFalse(r, r.contains("netherite"));
        assertEquals("neoforge", api.pulls.get(api.pulls.size() - 1).getAsJsonObject("servidor").get("plataforma").getAsString());
        assertEquals("[\"kit\"]", api.pulls.get(api.pulls.size() - 1).get("recursos").toString());

        // O kit da compra conta como o primeiro: o /kit espera a hora
        noJogo(() -> {
            try {
                d.execute("kit vip-diamante", new Fonte(kiteiro));
            } catch (CommandSyntaxException x) {
                throw new IllegalStateException(x);
            }
        });
        esperar("a resposta do /kit", () -> rodados.stream().anyMatch(r ->
                r.startsWith("tellraw Kiteiro \"§eEspere ") && r.endsWith(" para pegar o kit VIP Diamante de novo.\"")));
        assertEquals(rodados.toString(), 1, rodados.stream().filter(r -> r.contains("diamond_helmet")).count());
    }
}
