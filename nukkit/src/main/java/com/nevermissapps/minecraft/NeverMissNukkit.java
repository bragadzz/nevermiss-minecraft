package com.nevermissapps.minecraft;

import org.powernukkitx.Player;
import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.command.ConsoleCommandSender;
import org.powernukkitx.event.EventPriority;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.player.PlayerJoinEvent;
import org.powernukkitx.event.player.PlayerQuitEvent;
import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.plugin.PluginManager;
import org.powernukkitx.scheduler.TaskHandler;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Servidor Bedrock de verdade (PowerNukkitX 3): o jogador entra com o gamertag, que pode ter espaço.
 * Os comandos rodam no console, na thread principal. O Kit da NeverMiss ainda não sai aqui (o core recusa).
 */
public final class NeverMissNukkit extends PluginBase implements Listener, Jogo {
    private Laco laco;

    @Override
    public void onEnable() {
        try {
            laco = new Laco(this, getDataFolder(), getDescription().getVersion());
        } catch (IOException e) {
            getLogger().warning("Não consegui abrir " + getDataFolder() + " (" + e.getMessage() + "). Nada vai ser entregue.");
            return;
        }
        // Sem o @EventHandler: o PowerNukkitX compila o executor dele com reflexão em java.lang, que pede o --add-opens.
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvent(PlayerJoinEvent.class, this, EventPriority.NORMAL, (l, e) -> laco.entrou(((PlayerJoinEvent) e).getPlayer().getName()), this);
        pm.registerEvent(PlayerQuitEvent.class, this, EventPriority.NORMAL, (l, e) -> laco.saiu(((PlayerQuitEvent) e).getPlayer().getName()), this);
        laco.configurar();
    }

    @Override
    public void onDisable() {
        if (laco != null) laco.desligar();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (laco == null) return true;
        laco.comando(args, sender instanceof ConsoleCommandSender, texto -> getServer().getScheduler().scheduleTask(this, () -> sender.sendMessage(texto), false));
        return true;
    }

    // --- Jogo ---

    @Override
    public boolean rodar(String comando) {
        // 0 = falhou; -1 = não existe ou sem permissão
        return getServer().executeCommand(getServer().getConsoleSender(), comando) > 0;
    }

    @Override
    public boolean online(String nick) {
        return getServer().getPlayerExact(nick) != null;
    }

    @Override
    public UUID uuid(String nick) {
        Player p = getServer().getPlayerExact(nick);
        return p == null ? null : p.getUniqueId();
    }

    @Override
    public boolean onlineMode() {
        return getServer().getSettings().baseSettings().xboxAuth();
    }

    @Override
    public String versao() {
        return getServer().getName() + " " + getServer().getNukkitVersion() + " (MC: " + getServer().getVersion() + ")";
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
        if (getServer().isPrimaryThread()) return tarefa.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        getServer().getScheduler().scheduleTask(this, () -> {
            try {
                f.complete(tarefa.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        }, false);
        // Servidor desligando: a tarefa pode não rodar. O que não rodou volta pela fila e o executadas.txt segura.
        return f.get(60, TimeUnit.SECONDS);
    }

    @Override
    public Runnable depois(long segundos, Runnable tarefa) {
        TaskHandler t = getServer().getScheduler().scheduleDelayedTask(this, tarefa, (int) (segundos * 20), true);
        return t::cancel;
    }

    @Override
    public String plataforma() {
        return "nukkit";
    }

    @Override
    public String edicao() {
        return "bedrock";
    }

    /** O gamertag com espaço vai entre aspas: é como o comando do Bedrock (e o dos plugins do Nukkit) separa o nome. */
    @Override
    public String nomeNoComando(String nome) {
        return nome.indexOf(' ') >= 0 ? "\"" + nome + "\"" : nome;
    }

    @Override
    public Bedrock bedrock() {
        return null;
    }
}
