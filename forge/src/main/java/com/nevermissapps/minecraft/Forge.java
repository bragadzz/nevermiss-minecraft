package com.nevermissapps.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Forge 1.16.5 ao 1.20.1 e NeoForge 1.20.4 ao 26.x, num jar só, só no servidor (o jogador não instala nada).
 * Os comandos vão direto no Brigadier do servidor, que é o mesmo em todos. O pouco que vem do jogo vai por reflexão, com o
 * nome de cada época: o SRG do Forge 1.17 a 1.20.1 ("m_129893_"), o SRG antigo do Forge 1.16.5 ("func_195573_aM") e o da
 * Mojang no NeoForge ("createCommandSourceStack"). Os nomes saíram dos mappings oficiais da Mojang juntados com o MCPConfig de
 * cada versão. Os eventos do Forge e do NeoForge vão pelo nome da classe.
 */
final class Forge implements Jogo {
    private static final Logger LOG = LogManager.getLogger("NeverMiss");
    private static final Class<?>[] NADA = {};
    // {SRG do Forge 1.17 a 1.20.1, SRG do Forge 1.16.5, Mojang (NeoForge)}
    private static final String[] FONTE_DO_CONSOLE = {"m_129893_", "func_195573_aM", "createCommandSourceStack"};
    private static final String[] ORIGINAL = {"m_129797_", "func_71266_T", "usesAuthentication"};
    private static final String[] VERSAO_MC = {"m_7630_", "func_71249_w", "getServerVersion"};
    private static final String[] LISTA = {"m_6846_", "func_184103_al", "getPlayerList"};
    private static final String[] POR_NOME = {"m_11255_", "func_152612_a", "getPlayerByName"};
    // No 1.16.5 o inventário é um campo (INVENTARIO_116)
    private static final String[] INVENTARIO = {"m_150109_", "getInventory"};
    private static final String INVENTARIO_116 = "field_71071_by";
    private static final String[] ITEM = {"m_8020_", "func_70301_a", "getItem"};
    private static final String[] VAZIO = {"m_41619_", "func_190926_b", "isEmpty"};
    private static final String[] ENTIDADE = {"m_81373_", "func_197022_f", "getEntity"};
    private static final String[] PERFIL = {"m_36316_", "func_146103_bH", "getGameProfile"};
    // O GameProfile do authlib: getName() até o 1.21.8; depois virou record, name()
    private static final String[] NOME_DO_PERFIL = {"getName", "name"};
    private static final String[] ID_DO_PERFIL = {"getId", "id"};
    private static final String[] VERSAO_DO_CARREGADOR = {"net.minecraftforge.versions.forge.ForgeVersion",
            "net.neoforged.neoforge.common.NeoForgeVersion", "net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion"};

    private final String plataforma;
    private final File pasta;
    private final Set<String> online = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService agenda = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "NeverMiss");
        t.setDaemon(true);
        return t;
    });
    private volatile CommandDispatcher<Object> dispatcher;
    private volatile Object servidor;
    private volatile Object console;
    private volatile Thread principal;
    private volatile boolean parando;
    private volatile int[] versaoMc;
    private volatile String versao = "?";
    private volatile Bedrock bedrock;
    private volatile Laco laco;

    /**
     * Na carga do mod. Num Forge que não tem o que o mod usa (o 1.21.6+ trocou o barramento de eventos) o servidor sobe do mesmo
     * jeito: só avisa no console e não entrega nada, como o Fabric.
     */
    static void criar(String plataforma, String barramento, String eventos, File pasta) {
        try {
            new Forge(plataforma, barramento, eventos, pasta);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.error("[NeverMiss] Este " + (plataforma.equals("forge") ? "Forge" : "NeoForge") + " não tem o que o NeverMiss usa (" + e
                    + "). Nada vai ser entregue. O jar é do Forge 1.16.5 ao 1.20.1 e do NeoForge 1.20.4 ao 26.x.");
        }
    }

    /** barramento: a classe com o EVENT_BUS do jogo; eventos: o pacote dos eventos (o do Forge ou o do NeoForge). */
    Forge(String plataforma, String barramento, String eventos, File pasta) throws ReflectiveOperationException {
        this.plataforma = plataforma;
        this.pasta = pasta;
        Field campo = Class.forName(barramento).getField("EVENT_BUS");
        Object bus = campo.get(null);
        Method adicionar = adicionar(campo.getType());
        // Por último: o /kit de outro mod já está lá, e o da NeverMiss não se mistura com ele (ver comandos)
        ouvir(bus, adicionar, "LOWEST", this::comandos, eventos + "RegisterCommandsEvent");
        // O evento do servidor ligado mudou de pacote no Forge 1.17 e de novo no 1.18
        ouvir(bus, adicionar, "NORMAL", this::ligou, eventos + "server.ServerStartedEvent",
                "net.minecraftforge.fmlserverevents.FMLServerStartedEvent", "net.minecraftforge.fml.event.server.FMLServerStartedEvent");
        ouvir(bus, adicionar, "NORMAL", e -> desligou(), eventos + "server.ServerStoppingEvent",
                "net.minecraftforge.fmlserverevents.FMLServerStoppingEvent", "net.minecraftforge.fml.event.server.FMLServerStoppingEvent");
        ouvir(bus, adicionar, "NORMAL", e -> entrou(nome(chamar(e, "getEntity"))), eventos + "entity.player.PlayerEvent$PlayerLoggedInEvent");
        ouvir(bus, adicionar, "NORMAL", e -> saiu(nome(chamar(e, "getEntity"))), eventos + "entity.player.PlayerEvent$PlayerLoggedOutEvent");
    }

    /** addListener(prioridade, recebe cancelados, classe do evento, tratador): o mesmo no barramento do Forge e no do NeoForge. */
    private static Method adicionar(Class<?> bus) throws NoSuchMethodException {
        for (Method m : bus.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("addListener") && p.length == 4 && p[0].isEnum() && p[1] == boolean.class && p[2] == Class.class) return m;
        }
        throw new NoSuchMethodException(bus.getName() + ".addListener");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void ouvir(Object bus, Method adicionar, String prioridade, Consumer<Object> tratador, String... classes) throws ReflectiveOperationException {
        Class<?> evento = null;
        for (int i = 0; evento == null && i < classes.length; i++) {
            try {
                evento = Class.forName(classes[i], false, Forge.class.getClassLoader());
            } catch (ClassNotFoundException proxima) {
                // a classe de outra versão
            }
        }
        if (evento == null) throw new ClassNotFoundException(classes[0]);
        Consumer<Object> seguro = e -> {
            try {
                tratador.accept(e);
            } catch (RuntimeException x) {
                LOG.error("Erro da NeverMiss no " + e.getClass().getSimpleName(), x);
            }
        };
        adicionar.invoke(bus, Enum.valueOf((Class) adicionar.getParameterTypes()[0], prioridade), false, evento, seguro);
    }

    // --- eventos ---

    /** Na carga dos comandos (e de novo a cada /reload, com um Brigadier novo). */
    @SuppressWarnings("unchecked")
    private void comandos(Object evento) {
        CommandDispatcher<Object> d = (CommandDispatcher<Object>) chamar(evento, "getDispatcher");
        // Quem pode usar o /op do jogo (nível 3) usa o /nevermiss: a regra é a do próprio jogo, igual em toda versão.
        CommandNode<Object> op = d.getRoot().getChild("op");
        Predicate<Object> admin = op != null ? op.getRequirement() : s -> false;
        d.register(LiteralArgumentBuilder.<Object>literal("nevermiss").requires(admin)
                .executes(c -> nevermiss(c.getSource(), ""))
                .then(RequiredArgumentBuilder.<Object, String>argument("args", StringArgumentType.greedyString())
                        .executes(c -> nevermiss(c.getSource(), StringArgumentType.getString(c, "args")))));
        for (String nome : new String[]{"kit", "nmkit"}) {
            // O Brigadier juntaria os dois /kit num só: o de outro mod fica, e o da NeverMiss vai só no /nmkit.
            if (nome.equals("kit") && d.getRoot().getChild("kit") != null) {
                LOG.info("O /kit já é de outro mod: o da NeverMiss fica no /nmkit.");
                continue;
            }
            d.register(LiteralArgumentBuilder.<Object>literal(nome)
                    .executes(c -> kit(c.getSource(), ""))
                    .then(RequiredArgumentBuilder.<Object, String>argument("nome", StringArgumentType.greedyString())
                            .executes(c -> kit(c.getSource(), StringArgumentType.getString(c, "nome")))));
        }
        dispatcher = d;
    }

    /** Na thread do jogo, com o mundo carregado. */
    private void ligou(Object evento) {
        Object s = chamar(evento, "getServer");
        principal = Thread.currentThread();
        parando = false;
        console = chamar(s, FONTE_DO_CONSOLE);
        String mc = (String) chamar(s, VERSAO_MC);
        versaoMc = Kit.versao(mc);
        versao = (plataforma.equals("forge") ? "Forge " : "NeoForge ") + versaoDoCarregador() + " (MC: " + mc + ")";
        bedrock = Bedrock.detectar(Forge.class.getClassLoader());
        servidor = s;
        Laco l;
        try {
            l = new Laco(this, pasta, versaoDoMod());
        } catch (IOException e) {
            LOG.error("Não consegui abrir " + pasta + " (" + e.getMessage() + "). Nada vai ser entregue.");
            return;
        }
        laco = l;
        l.configurar();
    }

    private void desligou() {
        parando = true;
        Laco l = laco;
        laco = null;
        online.clear();
        if (l != null) l.desligar();
    }

    private void entrou(String nome) {
        if (nome == null) return;
        online.add(nome.toLowerCase(Locale.ROOT));
        Laco l = laco;
        if (l != null) l.entrou(nome);
    }

    private void saiu(String nome) {
        if (nome == null) return;
        online.remove(nome.toLowerCase(Locale.ROOT));
        Laco l = laco;
        if (l != null) l.saiu(nome);
    }

    // --- /nevermiss, /kit e /nmkit (na thread do jogo) ---

    private int nevermiss(Object fonte, String texto) {
        Object entidade = chamar(fonte, ENTIDADE);
        String nome = nome(entidade);
        Laco l = laco;
        // Sem entidade é o console (ou o RCON); os blocos de comando não passam no nível do /op.
        if (l == null) LOG.info("[NeverMiss] Ainda ligando.");
        else l.comando(palavras(texto), entidade == null, r -> responder(nome, r));
        return 1;
    }

    private int kit(Object fonte, String texto) {
        String nome = nome(chamar(fonte, ENTIDADE));
        Laco l = laco;
        if (nome == null || l == null) LOG.info("[NeverMiss] O /kit é do jogador, com o servidor ligado.");
        else l.kit(nome, palavras(texto), r -> responder(nome, r));
        return 1;
    }

    private static String[] palavras(String texto) {
        String t = texto.trim();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }

    /** De qualquer thread: no console vai para o log; ao jogador, a mensagem, na thread do jogo. */
    private void responder(String nome, String texto) {
        if (nome == null) LOG.info(texto);
        else if (Thread.currentThread() == principal) mensagem(nome, texto);
        else ((Executor) servidor).execute(() -> {
            if (Thread.currentThread() == principal) mensagem(nome, texto);
        });
    }

    // --- Jogo ---

    @Override
    public boolean rodar(String comando) {
        try {
            dispatcher.execute(comando, console);
            return true;
        } catch (CommandSyntaxException e) {
            // O comando que não existe e o que o jogo recusa ("No player was found") chegam aqui.
            LOG.warn("O jogo recusou: " + e.getMessage());
            return false;
        } catch (UnsupportedOperationException e) {
            // 1.20.3+ (só o NeoForge): o function e o return só rodam pelo caminho do próprio jogo, que não diz se deu certo
            // (como no Bukkit). Nada rodou antes: eles recusam já na entrada.
            Object comandos = chamar(servidor, "getCommands");
            Method m = metodo(comandos.getClass(), new String[]{"performPrefixedCommand"}, console.getClass(), String.class);
            if (m == null) throw e;
            invocar(m, comandos, console, comando);
            return true;
        }
    }

    @Override
    public boolean online(String nick) {
        return online.contains(nick.toLowerCase(Locale.ROOT));
    }

    /** Só em servidor original (o core não pergunta no pirata). Online, o do jogador (o do Floodgate, no Bedrock); offline, a Mojang. */
    @Override
    public UUID uuid(String nick) {
        if (online(nick)) {
            try {
                UUID u = naPrincipal(() -> {
                    Object jogador = chamar(chamar(servidor, LISTA), POR_NOME, String.class, nick);
                    return jogador == null ? null : (UUID) chamar(chamar(jogador, PERFIL), ID_DO_PERFIL);
                });
                if (u != null) return u;
            } catch (Exception saiuOuDesligando) {
                // vai pela Mojang
            }
        }
        return Nucleo.uuidMojang(nick);
    }

    @Override
    public boolean onlineMode() {
        return Boolean.TRUE.equals(chamar(servidor, ORIGINAL));
    }

    @Override
    public String versao() {
        return versao;
    }

    @Override
    public void log(String texto) {
        LOG.info(texto);
    }

    @Override
    public void aviso(String texto) {
        LOG.warn(texto);
    }

    @Override
    public <T> T naPrincipal(Callable<T> tarefa) throws Exception {
        if (Thread.currentThread() == principal) return tarefa.call();
        if (parando) throw new IllegalStateException("o servidor está desligando");
        FutureTask<T> f = new FutureTask<>(() -> {
            // Com o servidor já parado, o execute do jogo roda a tarefa aqui mesmo, fora da thread do jogo: aí nada roda.
            if (Thread.currentThread() != principal) throw new IllegalStateException("o servidor está desligando");
            return tarefa.call();
        });
        ((Executor) servidor).execute(f);
        return f.get(60, TimeUnit.SECONDS);
    }

    @Override
    public Runnable depois(long segundos, Runnable tarefa) {
        ScheduledFuture<?> f = agenda.schedule(() -> {
            try {
                tarefa.run();
            } catch (RuntimeException e) {
                LOG.warn("Erro da NeverMiss: " + e);
            }
        }, segundos, TimeUnit.SECONDS);
        return () -> f.cancel(false);
    }

    @Override
    public String plataforma() {
        return plataforma;
    }

    @Override
    public Bedrock bedrock() {
        return bedrock;
    }

    // --- Kit: o core monta os comandos vanilla (give, item replace); aqui só a conferência ---

    @Override
    public int[] versaoMc() {
        return versaoMc;
    }

    /** O item é conferido no registro do jogo já na leitura do comando: o give inteiro só é reconhecido se o item existe. */
    @Override
    public boolean existe(String item) {
        return reconhece("give @s minecraft:" + item);
    }

    @Override
    public boolean existeEncantamento(String id) {
        return reconhece("enchant @s minecraft:" + id);
    }

    private boolean reconhece(String comando) {
        ParseResults<Object> p = dispatcher.parse(comando, console);
        return !p.getReader().canRead() && p.getContext().getCommand() != null;
    }

    @Override
    public boolean vazio(String nome, int espaco) {
        Object jogador = chamar(chamar(servidor, LISTA), POR_NOME, String.class, nome);
        if (jogador == null) return false;
        return Boolean.TRUE.equals(chamar(chamar(inventario(jogador), ITEM, int.class, espaco), VAZIO));
    }

    private static Object inventario(Object jogador) {
        Method m = metodo(jogador.getClass(), INVENTARIO);
        if (m != null) return invocar(m, jogador);
        try {
            return jogador.getClass().getField(INVENTARIO_116).get(jogador);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("o jogador sem inventário nesta versão", e);
        }
    }

    /** Pelo tellraw, que existe em toda versão: o texto vai como string (JSON até o 1.21.4, SNBT depois; as duas leem igual). */
    @Override
    public void mensagem(String nome, String texto) {
        rodar("tellraw " + nome + " \"" + texto.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }

    // --- reflexão ---

    /** O nome do jogador, o do perfil. null se a entidade não é um jogador. */
    private static String nome(Object entidade) {
        Method perfil = entidade == null ? null : metodo(entidade.getClass(), PERFIL);
        return perfil == null ? null : (String) chamar(invocar(perfil, entidade), NOME_DO_PERFIL);
    }

    /** O método público com o primeiro destes nomes que existir. null se nenhum. */
    private static Method metodo(Class<?> classe, String[] nomes, Class<?>... tipos) {
        for (String n : nomes) {
            try {
                return classe.getMethod(n, tipos);
            } catch (NoSuchMethodException proximo) {
                // o nome de outra versão
            }
        }
        return null;
    }

    private static Object chamar(Object alvo, String... nomes) {
        return chamar(alvo, nomes, NADA);
    }

    private static Object chamar(Object alvo, String[] nomes, Class<?> tipo, Object arg) {
        return chamar(alvo, nomes, new Class<?>[]{tipo}, arg);
    }

    private static Object chamar(Object alvo, String[] nomes, Class<?>[] tipos, Object... args) {
        Method m = metodo(alvo.getClass(), nomes, tipos);
        if (m == null) throw new IllegalStateException("esta versão do jogo não tem " + nomes[nomes.length - 1] + " em " + alvo.getClass().getName());
        return invocar(m, alvo, args);
    }

    private static Object invocar(Method m, Object alvo, Object... args) {
        try {
            return m.invoke(alvo, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            throw c instanceof RuntimeException ? (RuntimeException) c : new IllegalStateException(c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String versaoDoCarregador() {
        for (String c : VERSAO_DO_CARREGADOR) {
            try {
                return (String) Class.forName(c, true, Forge.class.getClassLoader()).getMethod("getVersion").invoke(null);
            } catch (ReflectiveOperationException | LinkageError proxima) {
                // a classe do outro carregador ou de outra versão
            }
        }
        return "?";
    }

    private static String versaoDoMod() throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Forge.class.getResourceAsStream("versao.txt"), StandardCharsets.UTF_8))) {
            return r.readLine().trim();
        }
    }
}
