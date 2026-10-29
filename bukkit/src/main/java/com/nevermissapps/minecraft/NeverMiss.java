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

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/** Spigot, Paper e forks, do 1.7.10 ao mais novo, e o Folia, num jar só. */
public final class NeverMiss extends JavaPlugin implements Listener, Jogo {
    private final Set<String> online = ConcurrentHashMap.newKeySet();
    private Agenda agenda;
    private Bedrock bedrock;
    private Laco laco;

    @Override
    public void onEnable() {
        agenda = new Agenda(this);
        bedrock = Bedrock.detectar(getClass().getClassLoader());
        try {
            laco = new Laco(this, getDataFolder(), getDescription().getVersion());
        } catch (IOException e) {
            getLogger().severe("Não consegui abrir " + getDataFolder() + " (" + e.getMessage() + "). Nada vai ser entregue.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (Player p : jogadores()) online.add(p.getName().toLowerCase(Locale.ROOT));
        getServer().getPluginManager().registerEvents(this, this);
        laco.configurar();
    }

    @Override
    public void onDisable() {
        if (laco != null) laco.desligar();
    }

    /** No 1.7.10 o getOnlinePlayers devolve Player[]; do 1.8 em diante, uma Collection. */
    @SuppressWarnings("unchecked")
    private static Collection<? extends Player> jogadores() {
        try {
            Object r = Bukkit.class.getMethod("getOnlinePlayers").invoke(null);
            return r instanceof Player[] ? Arrays.asList((Player[]) r) : (Collection<? extends Player>) r;
        } catch (ReflectiveOperationException e) {
            return Collections.emptyList();
        }
    }

    @EventHandler
    public void entrou(PlayerJoinEvent e) {
        String nick = e.getPlayer().getName();
        online.add(nick.toLowerCase(Locale.ROOT));
        laco.entrou(nick);
    }

    @EventHandler
    public void saiu(PlayerQuitEvent e) {
        online.remove(e.getPlayer().getName().toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        laco.comando(args, sender instanceof ConsoleCommandSender, texto -> agenda.responder(sender, texto));
        return true;
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
        return agenda.naPrincipal(tarefa);
    }

    @Override
    public Runnable depois(long segundos, Runnable tarefa) {
        return agenda.depois(segundos, tarefa);
    }

    @Override
    public String plataforma() {
        return agenda.folia ? "folia" : "bukkit";
    }

    @Override
    public Bedrock bedrock() {
        return bedrock;
    }
}
