package com.nevermissapps.minecraft;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class NeverMiss extends JavaPlugin implements Listener, Jogo {
    private final Set<String> online = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean emCiclo = new AtomicBoolean();
    private volatile boolean deNovo;
    private volatile CommandSender pediuTeste;
    private Registro registro;
    private Nucleo nucleo;
    private BukkitTask proxima;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            registro = new Registro(new File(getDataFolder(), "executadas.txt"));
        } catch (IOException e) {
            getLogger().severe("Não consegui abrir executadas.txt (" + e.getMessage() + "). Nada vai ser entregue.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        nucleo = new Nucleo(this, registro, getDescription().getVersion());
        for (Player p : getServer().getOnlinePlayers()) online.add(p.getName().toLowerCase(Locale.ROOT));
        getServer().getPluginManager().registerEvents(this, this);
        configurar();
    }

    @Override
    public void onDisable() {
        if (registro != null) registro.fechar();
    }

    private void configurar() {
        reloadConfig();
        String chave = getConfig().getString("chave", "").trim();
        String origem = "config.yml";
        String env = System.getenv("NEVERMISS_CHAVE");
        if (chave.isEmpty() && env != null && !env.trim().isEmpty()) {
            chave = env.trim();
            origem = "variável NEVERMISS_CHAVE";
        }
        String api = Nucleo.validarApi(getConfig().getString("api", Nucleo.API_PADRAO));
        String erro = null;
        if (api == null) erro = "O endereço em 'api' do config.yml precisa ser https.";
        else if (chave.isEmpty()) erro = "Sem chave. No console: nevermiss chave <chave do painel>";
        else if (!Nucleo.CHAVE.matcher(chave).matches()) erro = "Essa chave não é da NeverMiss (começa com nmmc_).";
        if (erro != null) {
            nucleo.configurar(api, null);
            nucleo.situacao = erro;
            getLogger().warning(erro);
            cancelar();
            return;
        }
        if (api.startsWith("http:")) getLogger().warning("API sem https em " + api + ": use só para teste.");
        nucleo.configurar(api, chave);
        getLogger().info("Chave ****" + chave.substring(chave.length() - 4) + " (" + origem + "). Primeira consulta em 5 s.");
        agendar(5);
    }

    private synchronized void cancelar() {
        if (proxima != null) proxima.cancel();
        proxima = null;
    }

    private synchronized void agendar(long segundos) {
        cancelar();
        if (isEnabled()) proxima = getServer().getScheduler().runTaskLaterAsynchronously(this, this::volta, Math.max(1, segundos) * 20);
    }

    /** Fora da thread principal. Uma volta por vez. */
    private void volta() {
        if (!emCiclo.compareAndSet(false, true)) {
            deNovo = true;
            return;
        }
        int segundos;
        try {
            segundos = nucleo.ciclo();
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Erro na consulta à NeverMiss", e);
            segundos = 60;
        } finally {
            emCiclo.set(false);
        }
        CommandSender quem = pediuTeste;
        pediuTeste = null;
        if (quem != null && isEnabled()) {
            String resposta = nucleo.situacao;
            getServer().getScheduler().runTask(this, () -> quem.sendMessage("[NeverMiss] " + resposta));
        }
        boolean adiantar = deNovo;
        deNovo = false;
        if (segundos > 0) agendar(adiantar ? 5 : segundos);
    }

    /** Consulta já, respeitando 5 s desde a última (a API recusa antes disso). */
    private void adiantar() {
        if (emCiclo.get()) {
            deNovo = true;
            return;
        }
        long passou = (System.currentTimeMillis() - nucleo.ultimaConsulta) / 1000;
        agendar(Math.max(1, 5 - passou));
    }

    @EventHandler
    public void entrou(PlayerJoinEvent e) {
        String nick = e.getPlayer().getName();
        online.add(nick.toLowerCase(Locale.ROOT));
        if (nucleo.configurado() && nucleo.aoEntrar(nick)) adiantar();
    }

    @EventHandler
    public void saiu(PlayerQuitEvent e) {
        online.remove(e.getPlayer().getName().toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status":
                for (String linha : nucleo.status()) sender.sendMessage(linha);
                return true;
            case "testar":
                if (!nucleo.configurado()) {
                    sender.sendMessage("[NeverMiss] " + nucleo.situacao);
                    return true;
                }
                pediuTeste = sender;
                sender.sendMessage("[NeverMiss] Consultando a NeverMiss...");
                adiantar();
                return true;
            case "recarregar":
                configurar();
                sender.sendMessage("[NeverMiss] Configuração recarregada. " + nucleo.situacao);
                return true;
            case "chave":
                if (!(sender instanceof ConsoleCommandSender)) {
                    sender.sendMessage("[NeverMiss] A chave só pode ser trocada no console do servidor.");
                    return true;
                }
                if (args.length != 2 || !Nucleo.CHAVE.matcher(args[1]).matches()) {
                    sender.sendMessage("[NeverMiss] Use: nevermiss chave <chave do painel, começa com nmmc_>");
                    return true;
                }
                getConfig().set("chave", args[1]);
                saveConfig();
                configurar();
                return true;
            default:
                sender.sendMessage("[NeverMiss] Use: /nevermiss status | testar | recarregar");
                return true;
        }
    }

    // --- Jogo ---

    @Override
    public boolean rodar(String comando) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), comando);
    }

    @Override
    public boolean online(String nick) {
        return online.contains(nick.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("deprecation")
    @Override
    public UUID uuid(String nick) {
        return Bukkit.getOfflinePlayer(nick).getUniqueId();
    }

    @Override
    public boolean onlineMode() {
        return Bukkit.getOnlineMode();
    }

    @Override
    public String versao() {
        return Bukkit.getVersion();
    }

    @Override
    public void log(String texto) {
        getLogger().info(texto);
    }

    @Override
    public void aviso(String texto) {
        getLogger().warning(texto);
    }

    @Override
    public <T> T naPrincipal(Callable<T> tarefa) throws Exception {
        if (Bukkit.isPrimaryThread()) return tarefa.call();
        return Bukkit.getScheduler().callSyncMethod(this, tarefa).get();
    }
}
