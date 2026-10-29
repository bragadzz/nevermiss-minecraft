package com.nevermissapps.minecraft;

import java.util.UUID;
import java.util.concurrent.Callable;

/** O que o núcleo usa da plataforma. Cada uma implementa (Bukkit, Velocity, BungeeCord); os testes, com um falso. */
interface Jogo {
    /** Roda um comando no console. Só na thread principal. */
    boolean rodar(String comando);

    /** Seguro em qualquer thread. Não diferencia maiúsculas. Nos proxies: conectado em qualquer servidor da rede. */
    boolean online(String nick);

    /** Pode consultar a Mojang: nunca na thread principal. */
    UUID uuid(String nick);

    boolean onlineMode();

    String versao();

    void log(String texto);

    void aviso(String texto);

    /** Roda na thread principal e espera o resultado. Nos proxies, que não têm uma, roda aqui mesmo. */
    <T> T naPrincipal(Callable<T> tarefa) throws Exception;

    /** Roda a tarefa fora da thread principal daqui a N segundos. Devolve o que a cancela. */
    Runnable depois(long segundos, Runnable tarefa);

    /** "bukkit", "folia", "velocity" ou "bungee" (a lista do contrato). */
    String plataforma();

    /** "java" ou "bedrock": a edição do servidor, não a do jogador. */
    default String edicao() {
        return "java";
    }

    /** O Floodgate, quando este servidor (ou proxy) aceita jogadores Bedrock. null = não aceita. */
    Bedrock bedrock();
}
