package com.nevermissapps.minecraft;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * O agendador do Bukkit ou o do Folia, no mesmo jar. No Folia o do Bukkit não existe: o laço vai para o
 * AsyncScheduler, os comandos para o GlobalRegionScheduler e a resposta a um jogador para o EntityScheduler dele.
 * O Folia vai por reflexão, para o jar continuar carregando do 1.7.10 ao mais novo.
 */
final class Agenda {
    private static final String FOLIA = "io.papermc.paper.threadedregions.";

    private final Plugin plugin;
    final boolean folia;
    private Object assincrono;
    private Object global;
    private Method depois;
    private Method cancelar;
    private Method executar;
    private Method doJogador;
    private Method executarNoJogador;

    Agenda(Plugin plugin) {
        this.plugin = plugin;
        folia = existe(FOLIA + "RegionizedServer");
        if (!folia) return;
        try {
            Class<?> async = Class.forName(FOLIA + "scheduler.AsyncScheduler");
            Class<?> regiao = Class.forName(FOLIA + "scheduler.GlobalRegionScheduler");
            assincrono = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            global = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            depois = async.getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
            cancelar = Class.forName(FOLIA + "scheduler.ScheduledTask").getMethod("cancel");
            executar = regiao.getMethod("execute", Plugin.class, Runnable.class);
            doJogador = Entity.class.getMethod("getScheduler");
            executarNoJogador = Class.forName(FOLIA + "scheduler.EntityScheduler")
                    .getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Folia sem o agendador esperado", e);
        }
    }

    Runnable depois(long segundos, Runnable tarefa) {
        if (!folia) {
            BukkitTask t = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, tarefa, segundos * 20);
            return t::cancel;
        }
        Consumer<Object> rodar = x -> tarefa.run();
        Object t = chamar(depois, assincrono, plugin, rodar, segundos, TimeUnit.SECONDS);
        return () -> chamar(cancelar, t);
    }

    <T> T naPrincipal(Callable<T> tarefa) throws Exception {
        if (!folia) return Bukkit.isPrimaryThread() ? tarefa.call() : Bukkit.getScheduler().callSyncMethod(plugin, tarefa).get();
        CompletableFuture<T> f = new CompletableFuture<>();
        Runnable r = () -> {
            try {
                f.complete(tarefa.call());
            } catch (Throwable x) {
                f.completeExceptionally(x);
            }
        };
        chamar(executar, global, plugin, r);
        // Desligando, a região global não roda mais nada: o que não rodou volta na próxima consulta.
        return f.get(60, TimeUnit.SECONDS);
    }

    /** Responde a quem mandou o /nevermiss, de qualquer thread. */
    void responder(CommandSender quem, String texto) {
        Runnable r = () -> quem.sendMessage(texto);
        if (!folia) {
            if (Bukkit.isPrimaryThread()) r.run();
            else Bukkit.getScheduler().runTask(plugin, r);
        } else if (quem instanceof Entity) {
            chamar(executarNoJogador, chamar(doJogador, quem), plugin, r, null, 1L);
        } else {
            chamar(executar, global, plugin, r);
        }
    }

    private static boolean existe(String classe) {
        try {
            Class.forName(classe);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
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
