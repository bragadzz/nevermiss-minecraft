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

    // --- Kit da NeverMiss (contrato, seção 17). O core monta os comandos vanilla; a plataforma só confere e roda. ---

    /** A versão do Minecraft ({1, 20, 4}; {26, 3, 0}): o core monta o comando do kit para ela. null = não dá kit (o proxy não tem inventário). */
    default int[] versaoMc() {
        return null;
    }

    /** O item (id vanilla sem "minecraft:"; antes do 1.13, o id daquela época) existe nesta versão. */
    default boolean existe(String item) {
        return false;
    }

    /** O encantamento (id vanilla sem "minecraft:") existe nesta versão. Só é perguntado do 1.13 em diante. */
    default boolean existeEncantamento(String id) {
        return false;
    }

    /** O espaço do inventário do jogador (0-8 a barra, 9-35, 36-39 a armadura dos pés à cabeça, 40 a mão secundária) está vazio. Thread principal. */
    default boolean vazio(String nome, int espaco) {
        return false;
    }

    /** Dá a peça ao jogador online, no espaço (ou -1 = onde couber, e o resto no chão). Thread principal. */
    default boolean darPeca(String nome, Kit.Peca peca, int espaco) {
        return rodar(Kit.comando(versaoMc(), nome, peca, espaco));
    }

    /** Uma mensagem para o jogador online. Thread principal. */
    default void mensagem(String nome, String texto) {
    }
}
