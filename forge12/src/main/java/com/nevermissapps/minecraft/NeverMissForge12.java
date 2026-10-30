package com.nevermissapps.minecraft;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Forge 1.12.2 (o FML antigo, sem o Brigadier), só no servidor: o jogador entra sem o mod (acceptableRemoteVersions "*").
 * O jogo em tempo de execução tem os nomes do MCP nas classes e os do SRG nos métodos (func_71187_D = getCommandManager):
 * os stubs (src/stubs, fora do jar) têm esses nomes, conferidos no MCPConfig 1.12.2 e nos mappings MCP stable_39.
 */
@Mod(modid = "nevermiss", useMetadata = true, serverSideOnly = true, acceptableRemoteVersions = "*")
public final class NeverMissForge12 implements Jogo {
    private static final Logger LOG = LogManager.getLogger("NeverMiss");

    private final Set<String> online = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService agenda = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "NeverMiss");
        t.setDaemon(true);
        return t;
    });
    private volatile MinecraftServer servidor;
    private volatile Laco laco;
    private volatile boolean parando;

    public NeverMissForge12() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Na thread do jogo, com o mundo carregado: é aqui que o FML antigo registra os comandos. */
    @Mod.EventHandler
    public void ligando(FMLServerStartingEvent e) {
        servidor = e.getServer();
        parando = false;
        e.registerServerCommand(new Comando("nevermiss", 3, (quem, args) ->
                laco.comando(args, quem instanceof MinecraftServer, r -> responder(quem, r))));
        // O registerServerCommand do 1.12 troca o comando que já tem o nome: o /kit de outro mod (registrado antes ou depois)
        // fica com ele, e o da NeverMiss segue no /nmkit.
        for (String nome : new String[]{"kit", "nmkit"}) {
            if (nome.equals("kit") && servidor.func_71187_D().func_71555_a().containsKey("kit")) {
                LOG.info("O /kit já é de outro mod: o da NeverMiss fica no /nmkit.");
                continue;
            }
            e.registerServerCommand(new Comando(nome, 0, (quem, args) -> {
                if (quem instanceof EntityPlayerMP) laco.kit(quem.func_70005_c_(), args, r -> responder(quem, r));
                else quem.func_145747_a(new TextComponentString("[NeverMiss] O /" + nome + " é do jogador."));
            }));
        }
        File pasta = new File("config", "nevermiss");
        try {
            laco = new Laco(this, pasta, versaoDoMod());
        } catch (IOException x) {
            LOG.error("Não consegui abrir " + pasta + " (" + x.getMessage() + "). Nada vai ser entregue.");
            return;
        }
        laco.configurar();
    }

    @Mod.EventHandler
    public void parando(FMLServerStoppingEvent e) {
        parando = true;
        Laco l = laco;
        laco = null;
        if (l != null) l.desligar();
    }

    @SubscribeEvent
    public void entrou(PlayerEvent.PlayerLoggedInEvent e) {
        String nome = e.player.func_70005_c_();
        online.add(nome.toLowerCase(Locale.ROOT));
        Laco l = laco;
        if (l != null) l.entrou(nome);
    }

    @SubscribeEvent
    public void saiu(PlayerEvent.PlayerLoggedOutEvent e) {
        String nome = e.player.func_70005_c_();
        online.remove(nome.toLowerCase(Locale.ROOT));
        Laco l = laco;
        if (l != null) l.saiu(nome);
    }

    /** De qualquer thread. O console (o próprio servidor) escreve no log. */
    private void responder(ICommandSender quem, String texto) {
        servidor.func_152344_a(() -> quem.func_145747_a(new TextComponentString(texto)));
    }

    /** Um comando do FML antigo, com os nomes do SRG (os do MCP no comentário). */
    private final class Comando extends CommandBase {
        private final String nome;
        private final int nivel;
        private final BiConsumer<ICommandSender, String[]> fazer;

        Comando(String nome, int nivel, BiConsumer<ICommandSender, String[]> fazer) {
            this.nome = nome;
            this.nivel = nivel;
            this.fazer = fazer;
        }

        // getName
        @Override
        public String func_71517_b() {
            return nome;
        }

        // getUsage
        @Override
        public String func_71518_a(ICommandSender quem) {
            return "/" + nome;
        }

        // getRequiredPermissionLevel
        @Override
        public int func_82362_a() {
            return nivel;
        }

        // checkPermission. No 1.12 quem não é op não usa comando nenhum (menos tell, help, me, trigger): o /kit libera aqui.
        @Override
        public boolean func_184882_a(MinecraftServer s, ICommandSender quem) {
            return nivel == 0 || quem.func_70003_b(nivel, nome);
        }

        // execute
        @Override
        public void func_184881_a(MinecraftServer s, ICommandSender quem, String[] args) {
            if (laco == null) quem.func_145747_a(new TextComponentString("[NeverMiss] Ainda ligando."));
            else fazer.accept(quem, args);
        }
    }

    // --- Jogo ---

    @Override
    public boolean rodar(String comando) {
        return servidor.func_71187_D().func_71556_a(servidor, comando) > 0;
    }

    @Override
    public boolean online(String nick) {
        return online.contains(nick.toLowerCase(Locale.ROOT));
    }

    /** Só em servidor original (o core não pergunta no pirata): a conta da Mojang, como nos proxies. */
    @Override
    public UUID uuid(String nick) {
        return Nucleo.uuidMojang(nick);
    }

    @Override
    public boolean onlineMode() {
        return servidor.func_71266_T();
    }

    @Override
    public String versao() {
        return "Forge " + ForgeVersion.getVersion() + " (MC: " + servidor.func_71249_w() + ")";
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
        if (servidor.func_152345_ab()) return tarefa.call();
        if (parando) throw new IllegalStateException("o servidor está desligando");
        FutureTask<T> f = new FutureTask<>(() -> {
            // Com o servidor já parado, o addScheduledTask roda a tarefa aqui mesmo, fora da thread do jogo: aí nada roda.
            if (!servidor.func_152345_ab()) throw new IllegalStateException("o servidor está desligando");
            return tarefa.call();
        });
        servidor.func_152344_a(f);
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
        return "forge";
    }

    @Override
    public Bedrock bedrock() {
        return null;
    }

    // --- Kit: o core monta os comandos vanilla do 1.12 (give e replaceitem); aqui só a conferência ---

    @Override
    public int[] versaoMc() {
        return Kit.versao(servidor.func_71249_w());
    }

    @Override
    public boolean existe(String item) {
        return ForgeRegistries.ITEMS.containsKey(new ResourceLocation("minecraft", item));
    }

    @Override
    public boolean vazio(String nome, int espaco) {
        EntityPlayerMP p = servidor.func_184103_al().func_152612_a(nome);
        return p != null && p.field_71071_by.func_70301_a(espaco).func_190926_b();
    }

    @Override
    public void mensagem(String nome, String texto) {
        EntityPlayerMP p = servidor.func_184103_al().func_152612_a(nome);
        if (p != null) p.func_145747_a(new TextComponentString(texto));
    }

    private static String versaoDoMod() throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                NeverMissForge12.class.getResourceAsStream("versao.txt"), StandardCharsets.UTF_8))) {
            return r.readLine().trim();
        }
    }
}
