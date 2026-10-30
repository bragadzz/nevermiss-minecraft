package com.nevermissapps.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fabric, do 1.16.5 ao 26.x, num jar só, só no servidor (o jogador não instala nada) e sem o Fabric API.
 * O pouco que vem do jogo vai por reflexão: pelo nome intermediário do Fabric, que é o mesmo do 1.16.5 ao 1.21.11, ou
 * pelo da Mojang no 26.x, que saiu sem ofuscação. Os comandos vão direto no Brigadier do servidor, que não muda de nome.
 * Sem os eventos do Fabric API, quem entrou e quem saiu vem da lista de jogadores, olhada a cada segundo.
 */
public final class NeverMissFabric implements DedicatedServerModInitializer, Jogo {
    private static final Logger LOG = LogManager.getLogger("NeverMiss");

    // nome em minúsculas → nome
    private final Map<String, String> online = new ConcurrentHashMap<>();
    private final Map<String, UUID> uuids = new ConcurrentHashMap<>();
    private final Map<Class<?>, DoJogador> classes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService agenda = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "NeverMiss");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean vigiando = new AtomicBoolean();
    private final AtomicBoolean desligou = new AtomicBoolean();
    private boolean mojang;
    private Object servidor;
    private Executor principal;
    private volatile Thread thread;
    private Method comandos, fonte, jogadores, rodando, despachante, doJogo, entidade, lista, porNome;
    private volatile Method nomeDoPerfil;
    private boolean onlineMode;
    private int[] versaoMc;
    private String versao;
    private File usercache;
    private Bedrock bedrock;
    private Laco laco;
    private boolean avisouVigia;

    @SuppressWarnings("deprecation")
    @Override
    public void onInitializeServer() {
        FabricLoader f = FabricLoader.getInstance();
        String mc = versaoDo(f, "minecraft");
        boolean mojang = !"intermediary".equals(f.getMappingResolver().getCurrentRuntimeNamespace());
        File pasta = f.getConfigDir().resolve("nevermiss").toFile();
        File jogo = f.getGameDir().toFile();
        String texto = "Fabric " + mc + " (loader " + versaoDo(f, "fabricloader") + ")";
        String plugin = versaoDo(f, "nevermiss");
        // Aqui o servidor ainda não existe: o Fabric Loader guarda ele logo depois de criar, e o ligar roda na thread dele.
        Thread espera = new Thread(() -> {
            Object s;
            for (int i = 1; (s = f.getGameInstance()) == null; i++) {
                if (i == 2400) LOG.warn("[NeverMiss] O servidor não apareceu em 2 min: nada vai ser entregue até ele aparecer.");
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!(s instanceof Executor)) {
                LOG.error("[NeverMiss] Este Minecraft (" + mc + ") não tem o servidor que o NeverMiss espera. Nada vai ser entregue.");
                return;
            }
            Object servidor = s;
            ((Executor) s).execute(() -> ligar(servidor, mojang, pasta, jogo, mc, texto, plugin));
        }, "NeverMiss");
        espera.setDaemon(true);
        espera.start();
    }

    private static String versaoDo(FabricLoader f, String mod) {
        return f.getModContainer(mod).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    /** Thread principal. mojang = os nomes da Mojang (26.x); senão, os intermediários. */
    void ligar(Object servidor, boolean mojang, File pasta, File jogo, String mc, String versao, String versaoPlugin) {
        this.servidor = servidor;
        this.mojang = mojang;
        this.versao = versao;
        principal = (Executor) servidor;
        thread = Thread.currentThread();
        versaoMc = Kit.versao(mc);
        usercache = new File(jogo, "usercache.json");
        try {
            Class<?> s = servidor.getClass();
            comandos = metodo(s, n("method_3734", "getCommands"));
            fonte = metodo(s, n("method_3739", "createCommandSourceStack"));
            jogadores = metodo(s, n("method_3760", "getPlayerList"));
            rodando = metodo(s, n("method_3806", "isRunning"));
            onlineMode = (Boolean) chamar(metodo(s, n("method_3828", "usesAuthentication")), servidor);
            despachante = metodo(comandos.getReturnType(), n("method_9235", "getDispatcher"));
            entidade = metodo(fonte.getReturnType(), n("method_9228", "getEntity"));
            lista = metodo(jogadores.getReturnType(), n("method_14571", "getPlayers"));
            porNome = metodo(jogadores.getReturnType(), n("method_14566", "getPlayerByName"), String.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.error("[NeverMiss] Este Minecraft (" + mc + ") não tem o que o NeverMiss usa (" + e + "). Nada vai ser entregue.");
            return;
        }
        try {
            // Do 1.19 em diante: o caminho do próprio jogo, para o function e o return do 1.20.3+ (ver rodar)
            doJogo = metodo(comandos.getReturnType(), n("method_44252", "performPrefixedCommand"), fonte.getReturnType(), String.class);
        } catch (NoSuchMethodException antigo) {
            doJogo = null;
        }
        bedrock = Bedrock.detectar(getClass().getClassLoader());
        if (!pasta.isDirectory() && !pasta.mkdirs()) {
            LOG.error("[NeverMiss] Não consegui criar " + pasta + ". Nada vai ser entregue.");
            return;
        }
        try {
            laco = new Laco(this, pasta, versaoPlugin);
        } catch (IOException e) {
            LOG.error("[NeverMiss] Não consegui abrir " + pasta + " (" + e.getMessage() + "). Nada vai ser entregue.");
            return;
        }
        vigiar();
        laco.configurar();
        agenda.scheduleWithFixedDelay(this::bater, 1, 1, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::desligar, "NeverMiss desligando"));
    }

    private String n(String intermediario, String daMojang) {
        return mojang ? daMojang : intermediario;
    }

    /** Fora da thread principal, a cada segundo. */
    private void bater() {
        try {
            if (!rodando()) desligar();
            else if (vigiando.compareAndSet(false, true)) principal.execute(this::vigiar);
        } catch (RuntimeException e) {
            // sem isto o agendador para de chamar
            aviso("Erro ao olhar o servidor: " + e);
        }
    }

    /** Thread principal: os comandos continuam registrados (o /reload troca o despachante) e quem entrou e saiu. */
    void vigiar() {
        thread = Thread.currentThread();
        try {
            garantirComandos();
            Map<String, String> agora = new HashMap<>();
            for (Object p : todos()) {
                String nome = nome(p);
                String k = nome.toLowerCase(Locale.ROOT);
                agora.put(k, nome);
                if (!uuids.containsKey(k)) uuids.put(k, (UUID) chamar(doJogador(p).uuid, p));
            }
            for (Map.Entry<String, String> e : agora.entrySet()) {
                if (online.putIfAbsent(e.getKey(), e.getValue()) == null) laco.entrou(e.getValue());
            }
            for (String k : new ArrayList<>(online.keySet())) {
                if (agora.containsKey(k)) continue;
                String nome = online.remove(k);
                uuids.remove(k);
                laco.saiu(nome);
            }
        } catch (RuntimeException e) {
            if (!avisouVigia) aviso("Não consegui ler os jogadores do servidor: " + e);
            avisouVigia = true;
        } finally {
            vigiando.set(false);
        }
    }

    void desligar() {
        if (!desligou.compareAndSet(false, true)) return;
        if (laco != null) laco.desligar();
        agenda.shutdownNow();
    }

    // --- Comandos (/nevermiss, /kit e /nmkit) ---

    private void garantirComandos() {
        CommandDispatcher<Object> d = despachante();
        if (d.getRoot().getChild("nevermiss") == null) {
            d.register(LiteralArgumentBuilder.literal("nevermiss").requires(this::admin)
                    .executes(c -> nevermiss(c, ""))
                    .then(RequiredArgumentBuilder.argument("args", StringArgumentType.greedyString())
                            .executes(c -> nevermiss(c, StringArgumentType.getString(c, "args")))));
        }
        // O /kit de outro mod fica com ele: o comprador usa o /nmkit.
        for (String k : new String[]{"kit", "nmkit"}) {
            if (d.getRoot().getChild(k) != null) continue;
            d.register(LiteralArgumentBuilder.literal(k)
                    .executes(c -> kit(c, null))
                    .then(RequiredArgumentBuilder.argument("nome", StringArgumentType.word())
                            .executes(c -> kit(c, StringArgumentType.getString(c, "nome")))));
        }
    }

    /** Quem pode o /op do jogo (nível 3) pode o /nevermiss, como o nevermiss.admin do Bukkit, que vem para op. */
    private boolean admin(Object fonte) {
        return pode("op", fonte);
    }

    private boolean pode(String comando, Object fonte) {
        CommandNode<Object> n = despachante().getRoot().getChild(comando);
        return n != null && n.canUse(fonte);
    }

    private int nevermiss(CommandContext<Object> c, String args) {
        Object fonte = c.getSource();
        Object ent = chamar(entidade, fonte);
        String jogador = ent == null ? null : nomeSeJogador(ent);
        // A chave só pelo console do servidor (ou o RCON): sem entidade e com o nível do /stop (4).
        boolean console = ent == null && pode("stop", fonte);
        String a = args.trim();
        laco.comando(a.isEmpty() ? new String[0] : a.split("\\s+"), console,
                jogador == null ? t -> LOG.info(t) : t -> responder(jogador, t));
        return 1;
    }

    private int kit(CommandContext<Object> c, String nome) {
        Object ent = chamar(entidade, c.getSource());
        String jogador = ent == null ? null : nomeSeJogador(ent);
        if (jogador == null) {
            LOG.info("[NeverMiss] O /kit é do jogador.");
            return 0;
        }
        laco.kit(jogador, nome == null ? new String[0] : new String[]{nome}, t -> responder(jogador, t));
        return 1;
    }

    /** De qualquer thread: a mensagem sai na thread principal. */
    private void responder(String jogador, String texto) {
        if (rodando()) principal.execute(() -> mensagem(jogador, texto));
    }

    // --- Jogo ---

    @Override
    public boolean rodar(String comando) {
        Object f = chamar(fonte, servidor);
        try {
            despachante().execute(comando, f);
            return true;
        } catch (CommandSyntaxException e) {
            aviso("O jogo recusou o comando: " + e.getMessage());
            return false;
        } catch (UnsupportedOperationException e) {
            // 1.20.3+: o function e o return só rodam pelo caminho do próprio jogo, que não diz se deu certo (como no Bukkit)
            if (doJogo == null) throw e;
            chamar(doJogo, chamar(comandos, servidor), f, comando);
            return true;
        }
    }

    @Override
    public boolean online(String nick) {
        return online.containsKey(nick.toLowerCase(Locale.ROOT));
    }

    @Override
    public UUID uuid(String nick) {
        UUID u = uuids.get(nick.toLowerCase(Locale.ROOT));
        if (u == null) u = doCache(nick);
        return u != null ? u : Nucleo.uuidMojang(nick);
    }

    /** O usercache.json, o cache de perfis do próprio servidor. null = não está lá (ou venceu): vai pela Mojang. */
    private UUID doCache(String nick) {
        if (!usercache.isFile()) return null;
        try (Reader r = Files.newBufferedReader(usercache.toPath(), StandardCharsets.UTF_8)) {
            SimpleDateFormat data = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
            for (JsonElement el : JsonParser.parseReader(r).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                if (!nick.equalsIgnoreCase(o.get("name").getAsString())) continue;
                if (data.parse(o.get("expiresOn").getAsString()).getTime() < System.currentTimeMillis()) return null;
                return UUID.fromString(o.get("uuid").getAsString());
            }
        } catch (IOException | ParseException | RuntimeException ilegivel) {
            // vai pela Mojang
        }
        return null;
    }

    @Override
    public boolean onlineMode() {
        return onlineMode;
    }

    @Override
    public String versao() {
        return versao;
    }

    @Override
    public void log(String texto) {
        LOG.info("[NeverMiss] " + texto);
    }

    @Override
    public void aviso(String texto) {
        LOG.warn("[NeverMiss] " + texto);
    }

    @Override
    public <T> T naPrincipal(Callable<T> tarefa) throws Exception {
        if (Thread.currentThread() == thread) return tarefa.call();
        if (!rodando()) throw new IllegalStateException("o servidor está desligando");
        FutureTask<T> f = new FutureTask<>(tarefa);
        principal.execute(f);
        try {
            return f.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // o que não rodou volta na próxima consulta
            f.cancel(false);
            throw e;
        }
    }

    @Override
    public Runnable depois(long segundos, Runnable tarefa) {
        try {
            ScheduledFuture<?> f = agenda.schedule(() -> {
                try {
                    tarefa.run();
                } catch (RuntimeException e) {
                    aviso("Erro: " + e);
                }
            }, segundos, TimeUnit.SECONDS);
            return () -> f.cancel(false);
        } catch (RejectedExecutionException desligando) {
            return () -> { };
        }
    }

    @Override
    public String plataforma() {
        return "fabric";
    }

    @Override
    public Bedrock bedrock() {
        return bedrock;
    }

    // --- Kit: o core monta o give e o item replace de cada versão; aqui só se confere e roda. ---

    @Override
    public int[] versaoMc() {
        return versaoMc;
    }

    /** Pelo analisador do próprio jogo: o give com esse item vale nesta versão. */
    @Override
    public boolean existe(String item) {
        return valido("give NeverMiss minecraft:" + item);
    }

    @Override
    public boolean existeEncantamento(String id) {
        return valido("enchant NeverMiss minecraft:" + id);
    }

    @Override
    public boolean vazio(String nome, int espaco) {
        Object p = chamar(porNome, chamar(jogadores, servidor), nome);
        if (p == null) return false;
        DoJogador d = doJogador(p);
        try {
            return (Boolean) chamar(d.vazio, chamar(d.item, d.inventario.get(p), espaco));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void mensagem(String nome, String texto) {
        try {
            despachante().execute("tellraw " + nome + " " + citado(texto), chamar(fonte, servidor));
        } catch (CommandSyntaxException | RuntimeException saiu) {
            // o jogador já saiu
        }
    }

    /** Entre aspas, com \ e " escapados: vale como texto em JSON (até o 1.21.4) e em SNBT (1.21.5+). */
    static String citado(String texto) {
        return "\"" + texto.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // --- O jogo por reflexão ---

    @SuppressWarnings("unchecked")
    private CommandDispatcher<Object> despachante() {
        return (CommandDispatcher<Object>) chamar(despachante, chamar(comandos, servidor));
    }

    /** O comando existe e os argumentos (o item, o encantamento) valem nesta versão, sem rodar. Thread principal. */
    private boolean valido(String comando) {
        ParseResults<Object> p = despachante().parse(comando, chamar(fonte, servidor));
        return !p.getReader().canRead() && p.getContext().getCommand() != null;
    }

    private boolean rodando() {
        return (Boolean) chamar(rodando, servidor);
    }

    /** Thread principal. */
    private List<?> todos() {
        return (List<?>) chamar(lista, chamar(jogadores, servidor));
    }

    /** O nome, se a entidade é um jogador no servidor. Thread principal. */
    private String nomeSeJogador(Object entidade) {
        for (Object p : todos()) if (p == entidade) return nome(p);
        return null;
    }

    private String nome(Object jogador) {
        Object perfil = chamar(doJogador(jogador).perfil, jogador);
        Method m = nomeDoPerfil;
        if (m == null) {
            // O GameProfile do authlib antigo tem getName(); o novo é um record, com name().
            try {
                m = metodo(perfil.getClass(), "getName");
            } catch (NoSuchMethodException novo) {
                try {
                    m = metodo(perfil.getClass(), "name");
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException("GameProfile sem o nome", e);
                }
            }
            nomeDoPerfil = m;
        }
        return (String) chamar(m, perfil);
    }

    /** O que se lê de um jogador, achado uma vez por classe (o jogador falso de um mod herda do ServerPlayer). */
    private final class DoJogador {
        final Method perfil;
        final Method uuid;
        final Field inventario;
        final Method item;
        final Method vazio;

        DoJogador(Class<?> c) throws ReflectiveOperationException {
            perfil = metodo(c, n("method_7334", "getGameProfile"));
            uuid = metodo(c, n("method_5667", "getUUID"));
            // O campo existe em todas as versões; o getInventory() só do 1.17 em diante.
            inventario = campo(c, n("field_7514", "inventory"));
            item = metodo(inventario.getType(), n("method_5438", "getItem"), int.class);
            vazio = metodo(item.getReturnType(), n("method_7960", "isEmpty"));
        }
    }

    private DoJogador doJogador(Object jogador) {
        return classes.computeIfAbsent(jogador.getClass(), c -> {
            try {
                return new DoJogador(c);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("o jogador não tem " + e.getMessage(), e);
            }
        });
    }

    /** O método, público ou não, da classe ou de uma acima dela (ou de uma interface). */
    static Method metodo(Class<?> c, String nome, Class<?>... tipos) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(nome, tipos);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException acima) {
                // procura na de cima
            }
        }
        Method m = c.getMethod(nome, tipos);
        m.setAccessible(true);
        return m;
    }

    static Field campo(Class<?> c, String nome) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(nome);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException acima) {
                // procura na de cima
            }
        }
        throw new NoSuchFieldException(nome);
    }

    private static Object chamar(Method m, Object alvo, Object... args) {
        try {
            return m.invoke(alvo, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            throw c instanceof RuntimeException ? (RuntimeException) c : new IllegalStateException(c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
