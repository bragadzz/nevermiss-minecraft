package com.nevermissapps.minecraft;

import java.util.UUID;
import java.util.concurrent.Callable;

/** O que o núcleo usa do servidor. O plugin implementa com o Bukkit; os testes, com um falso. */
interface Jogo {
    /** Roda um comando no console. Só na thread principal. */
    boolean rodar(String comando);

    /** Seguro em qualquer thread. Não diferencia maiúsculas. */
    boolean online(String nick);

    /** Pode consultar a Mojang: nunca na thread principal. */
    UUID uuid(String nick);

    boolean onlineMode();

    String versao();

    void log(String texto);

    void aviso(String texto);

    /** Roda na thread principal e espera o resultado. */
    <T> T naPrincipal(Callable<T> tarefa) throws Exception;
}
