package com.nevermissapps.minecraft;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** No proxy Velocity: os comandos rodam no console do proxy e "online" é estar em qualquer servidor da rede. */
public final class NeverMissVelocity implements Jogo {
    private final ProxyServer proxy;
    private final Logger logger;
    private final File pasta;
    private Bedrock bedrock;
    private Laco laco;

    @Inject
    public NeverMissVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path pasta) {
        this.proxy = proxy;
        this.logger = logger;
        this.pasta = pasta.toFile();
    }

    @Subscribe
    public void ligar(ProxyInitializeEvent e) {
        bedrock = Bedrock.detectar(getClass().getClassLoader());
        String versao = proxy.getPluginManager().fromInstance(this).flatMap(p -> p.getDescription().getVersion()).orElse("?");
        try {
            laco = new Laco(this, pasta, versao);
        } catch (IOException x) {
            logger.error("Não consegui abrir {} ({}). Nada vai ser entregue.", pasta, x.getMessage());
            return;
        }
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("nevermiss").build(), new SimpleCommand() {
            @Override
            public void execute(Invocation i) {
                laco.comando(i.arguments(), i.source() instanceof ConsoleCommandSource, t -> i.source().sendMessage(Component.text(t)));
            }

            @Override
            public boolean hasPermission(Invocation i) {
                return i.source().hasPermission("nevermiss.admin");
            }
        });
        laco.configurar();
    }

    @Subscribe
    public void desligar(ProxyShutdownEvent e) {
        if (laco != null) laco.desligar();
    }

    @Subscribe
    public void entrou(PostLoginEvent e) {
        if (laco != null) laco.entrou(e.getPlayer().getUsername());
    }

    // --- Jogo ---

    @Override
    public boolean rodar(String comando) {
        try {
            return proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), comando).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
        }
    }

    @Override
    public boolean online(String nick) {
        return proxy.getPlayer(nick).isPresent();
    }

    @Override
    public UUID uuid(String nick) {
        return proxy.getPlayer(nick).map(Player::getUniqueId).orElseGet(() -> Nucleo.uuidMojang(nick));
    }

    @Override
    public boolean onlineMode() {
        return proxy.getConfiguration().isOnlineMode();
    }

    @Override
    public String versao() {
        return proxy.getVersion().getName() + " " + proxy.getVersion().getVersion();
    }

    @Override
    public void log(String texto) {
        logger.info(texto);
    }

    @Override
    public void aviso(String texto) {
        logger.warn(texto);
    }

    @Override
    public <T> T naPrincipal(Callable<T> tarefa) throws Exception {
        return tarefa.call();
    }

    @Override
    public Runnable depois(long segundos, Runnable tarefa) {
        ScheduledTask t = proxy.getScheduler().buildTask(this, tarefa).delay(segundos, TimeUnit.SECONDS).schedule();
        return t::cancel;
    }

    @Override
    public String plataforma() {
        return "velocity";
    }

    @Override
    public Bedrock bedrock() {
        return bedrock;
    }
}
