package com.nevermissapps.minecraft;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** No proxy BungeeCord ou Waterfall: os comandos rodam no console do proxy e "online" é estar em qualquer servidor da rede. */
public final class NeverMissBungee extends Plugin implements Listener, Jogo {
    private Bedrock bedrock;
    private Laco laco;

    @Override
    public void onEnable() {
        bedrock = Bedrock.detectar(getClass().getClassLoader());
        try {
            laco = new Laco(this, getDataFolder(), getDescription().getVersion());
        } catch (IOException e) {
            getLogger().severe("Não consegui abrir " + getDataFolder() + " (" + e.getMessage() + "). Nada vai ser entregue.");
            return;
        }
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new Command("nevermiss", "nevermiss.admin") {
            @Override
            public void execute(CommandSender quem, String[] args) {
                laco.comando(args, !(quem instanceof ProxiedPlayer), t -> quem.sendMessage(new TextComponent(t)));
            }
        });
        laco.configurar();
    }

    @Override
    public void onDisable() {
        if (laco != null) laco.desligar();
    }

    @EventHandler
    public void entrou(PostLoginEvent e) {
        laco.entrou(e.getPlayer().getName());
    }

    // --- Jogo ---

    @Override
    public boolean rodar(String comando) {
        return getProxy().getPluginManager().dispatchCommand(getProxy().getConsole(), comando);
    }

    @Override
    public boolean online(String nick) {
        return getProxy().getPlayer(nick) != null;
    }

    @Override
    public UUID uuid(String nick) {
        ProxiedPlayer p = getProxy().getPlayer(nick);
        return p != null ? p.getUniqueId() : Nucleo.uuidMojang(nick);
    }

    @Override
    public boolean onlineMode() {
        return getProxy().getConfig().isOnlineMode();
    }

    @Override
    public String versao() {
        return getProxy().getName() + " " + getProxy().getVersion();
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
        return tarefa.call();
    }

    @Override
    public Runnable depois(long segundos, Runnable tarefa) {
        ScheduledTask t = getProxy().getScheduler().schedule(this, tarefa, segundos, TimeUnit.SECONDS);
        return t::cancel;
    }

    @Override
    public String plataforma() {
        return "bungee";
    }

    @Override
    public Bedrock bedrock() {
        return bedrock;
    }
}
