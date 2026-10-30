package com.nevermissapps.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * O módulo Fabric contra um "Minecraft" falso com os nomes da Mojang (os do 26.x) e o Brigadier de verdade: os comandos
 * registrados, o rodar que diz se deu certo, o item e o encantamento pelo analisador, quem entrou, o /kit e o /nevermiss.
 */
public class FabricTest {
    @Rule
    public TemporaryFolder pasta = new TemporaryFolder();

    private Servidor servidor;
    private NeverMissFabric mod;

    @Before
    public void antes() throws IOException {
        servidor = new Servidor();
        mod = new NeverMissFabric();
        mod.ligar(servidor, true, pasta.newFolder("nevermiss"), pasta.getRoot(), "1.21.1", "Fabric 1.21.1 (loader 0.20.1)", "1.2.0");
    }

    @After
    public void depois() {
        mod.desligar();
    }

    private void executar(Fonte f, String comando) throws CommandSyntaxException {
        servidor.comandos.d.execute(comando, f);
    }

    @Test
    public void rodaPeloBrigadierEDizSeDeuCerto() {
        assertTrue(mod.rodar("say oi"));
        assertFalse(mod.rodar("comandoquenaoexiste Steve"));
        assertFalse(mod.rodar("give Steve minecraft:netherite_sword"));
        // o comando existe, mas o jogo deu erro (o jogador não está)
        assertFalse(mod.rodar("give Ninguem minecraft:stone"));
        // 1.20.3+: o function só roda pelo caminho do jogo
        assertTrue(mod.rodar("function loja:vip"));
        assertEquals(Arrays.asList("say oi"), servidor.comandos.rodou);
        assertEquals(Arrays.asList("function loja:vip"), servidor.comandos.peloJogo);
    }

    @Test
    public void itemEEncantamentoPeloAnalisadorDoJogo() {
        assertTrue(mod.existe("diamond_sword"));
        assertFalse(mod.existe("netherite_sword"));
        assertTrue(mod.existeEncantamento("sharpness"));
        assertFalse(mod.existeEncantamento("swift_sneak"));
        assertEquals("fabric", mod.plataforma());
        assertArrayEquals(new int[]{1, 21, 1}, mod.versaoMc());
        assertTrue(servidor.tarefas.isEmpty());
    }

    @Test
    public void quemEntraSaiEUsaOKit() throws Exception {
        Jogador steve = new Jogador("Steve");
        steve.inventory.cheios.add(0);
        servidor.jogadores.lista.add(steve);
        mod.vigiar();
        assertTrue(mod.online("steve"));
        assertEquals(steve.perfil.id, mod.uuid("STEVE"));
        assertFalse(mod.vazio("Steve", 0));
        assertTrue(mod.vazio("Steve", 39));
        assertFalse(mod.vazio("Ninguem", 0));
        assertTrue(mod.rodar("give Steve minecraft:diamond_sword"));

        // Sem chave, a NeverMiss responde ao jogador (pelo tellraw, na thread principal) que não está configurada
        executar(new Fonte(0, steve), "kit vip-diamante");
        servidor.principal();
        assertTrue(servidor.comandos.rodou.toString(),
                servidor.comandos.rodou.contains("tellraw Steve \"§cA NeverMiss não está configurada neste servidor.\""));

        servidor.jogadores.lista.clear();
        mod.vigiar();
        assertFalse(mod.online("Steve"));
    }

    @Test
    public void nevermissSoParaOpEAChaveSoNoConsole() throws Exception {
        Jogador chefe = new Jogador("Chefe");
        servidor.jogadores.lista.add(chefe);
        mod.vigiar();
        try {
            executar(new Fonte(0, chefe), "nevermiss status");
            fail("jogador sem op usou o /nevermiss");
        } catch (CommandSyntaxException esperado) {
            // o jogo diz que o comando não existe para ele
        }
        executar(new Fonte(3, chefe), "nevermiss chave nmmc_" + new String(new char[43]).replace('\0', 'a'));
        servidor.principal();
        assertTrue(servidor.comandos.rodou.toString(),
                servidor.comandos.rodou.contains("tellraw Chefe \"[NeverMiss] A chave só pode ser trocada no console do servidor.\""));
        executar(new Fonte(4, null), "nevermiss status");
    }

    @Test
    public void oKitDeOutroModFicaEOReloadRegistraDeNovo() throws Exception {
        // O /reload troca o despachante; neste, outro mod já tem o /kit
        servidor.comandos = new Comandos(servidor.jogadores);
        int[] doOutro = {0};
        servidor.comandos.d.register(literal("kit").executes(c -> ++doOutro[0]));
        mod.vigiar();
        assertNotNull(servidor.comandos.d.getRoot().getChild("nevermiss"));
        assertNotNull(servidor.comandos.d.getRoot().getChild("nmkit"));
        executar(new Fonte(0, null), "kit");
        assertEquals(1, doOutro[0]);
    }

    @Test
    public void naPrincipalEsperaAThreadDoServidor() throws Exception {
        ExecutorService fora = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> f = fora.submit(() -> mod.naPrincipal(() -> 42));
            while (!f.isDone()) {
                servidor.principal();
                Thread.sleep(5);
            }
            assertEquals(42, (int) f.get());
        } finally {
            fora.shutdownNow();
        }
    }

    // --- O "Minecraft" falso: só os nomes que o NeverMissFabric procura ---

    public static final class Servidor implements Executor {
        final Queue<Runnable> tarefas = new ConcurrentLinkedQueue<>();
        final Jogadores jogadores = new Jogadores();
        volatile Comandos comandos = new Comandos(jogadores);

        public Comandos getCommands() {
            return comandos;
        }

        public Fonte createCommandSourceStack() {
            return new Fonte(4, null);
        }

        public Jogadores getPlayerList() {
            return jogadores;
        }

        public boolean usesAuthentication() {
            return false;
        }

        public boolean isRunning() {
            return true;
        }

        @Override
        public void execute(Runnable r) {
            tarefas.add(r);
        }

        /** Roda o que foi mandado para a thread principal. */
        void principal() {
            for (Runnable r; (r = tarefas.poll()) != null; ) r.run();
        }
    }

    public static final class Comandos {
        final CommandDispatcher<Object> d = new CommandDispatcher<>();
        final List<String> rodou = new ArrayList<>();
        final List<String> peloJogo = new ArrayList<>();

        Comandos(Jogadores jogadores) {
            d.register(literal("op").requires(f -> ((Fonte) f).nivel >= 3).executes(c -> 1));
            d.register(literal("stop").requires(f -> ((Fonte) f).nivel >= 4).executes(c -> 1));
            d.register(literal("say").then(argument("texto", greedyString()).executes(c -> {
                rodou.add("say " + getString(c, "texto"));
                return 1;
            })));
            d.register(literal("give").then(argument("alvo", word())
                    .then(argument("item", id("minecraft:diamond_sword", "minecraft:stone")).executes(c -> {
                        if (jogadores.getPlayerByName(getString(c, "alvo")) == null) {
                            throw new SimpleCommandExceptionType(new LiteralMessage("No player was found")).create();
                        }
                        return 1;
                    }))));
            d.register(literal("enchant").then(argument("alvo", word()).then(argument("enc", id("minecraft:sharpness")).executes(c -> 1))));
            d.register(literal("tellraw").then(argument("alvo", word()).then(argument("texto", greedyString()).executes(c -> {
                rodou.add("tellraw " + getString(c, "alvo") + " " + getString(c, "texto"));
                return 1;
            }))));
            d.register(literal("function").then(argument("nome", greedyString()).executes(c -> {
                throw new UnsupportedOperationException("This function should not run");
            })));
        }

        public CommandDispatcher<Object> getDispatcher() {
            return d;
        }

        public void performPrefixedCommand(Fonte f, String comando) {
            peloJogo.add(comando);
        }

        /** Um argumento que, como o item e o encantamento do jogo, recusa o id que não existe já na análise. */
        private static ArgumentType<String> id(String... validos) {
            Set<String> ok = new HashSet<>(Arrays.asList(validos));
            return r -> {
                int inicio = r.getCursor();
                while (r.canRead() && r.peek() != ' ') r.skip();
                String s = r.getString().substring(inicio, r.getCursor());
                if (ok.contains(s)) return s;
                r.setCursor(inicio);
                throw new SimpleCommandExceptionType(new LiteralMessage("Unknown " + s)).createWithContext(r);
            };
        }
    }

    public static final class Fonte {
        final int nivel;
        final Object entidade;

        Fonte(int nivel, Object entidade) {
            this.nivel = nivel;
            this.entidade = entidade;
        }

        public Object getEntity() {
            return entidade;
        }
    }

    public static final class Jogadores {
        final List<Jogador> lista = new ArrayList<>();

        public List<Jogador> getPlayers() {
            return lista;
        }

        public Jogador getPlayerByName(String nome) {
            for (Jogador j : lista) if (j.perfil.nome.equalsIgnoreCase(nome)) return j;
            return null;
        }
    }

    public static final class Jogador {
        final Perfil perfil;
        final Inventario inventory = new Inventario();

        Jogador(String nome) {
            perfil = new Perfil(nome);
        }

        public Perfil getGameProfile() {
            return perfil;
        }

        public UUID getUUID() {
            return perfil.id;
        }
    }

    /** Como o GameProfile do authlib novo, um record: name() e id(). */
    public static final class Perfil {
        final String nome;
        final UUID id = UUID.randomUUID();

        Perfil(String nome) {
            this.nome = nome;
        }

        public String name() {
            return nome;
        }
    }

    public static final class Inventario {
        final Set<Integer> cheios = new HashSet<>();

        public Pilha getItem(int espaco) {
            return new Pilha(!cheios.contains(espaco));
        }
    }

    public static final class Pilha {
        final boolean vazia;

        Pilha(boolean vazia) {
            this.vazia = vazia;
        }

        public boolean isEmpty() {
            return vazia;
        }
    }
}
