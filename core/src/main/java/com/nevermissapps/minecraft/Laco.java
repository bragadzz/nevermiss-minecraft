package com.nevermissapps.minecraft;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * O que é igual em todas as plataformas: o config.yml, o /nevermiss e quando consultar
 * (uma volta por vez, fora da thread principal). A plataforma liga os eventos e implementa o {@link Jogo}.
 */
final class Laco {
    private final Jogo jogo;
    private final File config;
    private final Registro registro;
    final Nucleo nucleo;
    private final AtomicBoolean emCiclo = new AtomicBoolean();
    private volatile boolean deNovo;
    private volatile boolean ligado = true;
    private volatile Consumer<String> pediuTeste;
    private Runnable proxima;

    Laco(Jogo jogo, File pasta, String versao) throws IOException {
        this.jogo = jogo;
        registro = new Registro(new File(pasta, "executadas.txt"));
        nucleo = new Nucleo(jogo, registro, versao);
        config = new File(pasta, "config.yml");
        if (!config.exists()) {
            try (InputStream in = Laco.class.getResourceAsStream("/config.yml")) {
                Files.copy(in, config.toPath());
            }
        }
        Bedrock b = jogo.bedrock();
        if (b != null) jogo.log("Floodgate encontrado (prefixo \"" + b.prefixo + "\"): entrega também para jogadores Bedrock.");
    }

    /** Lê o config.yml (ou a variável NEVERMISS_CHAVE) e liga o laço. */
    void configurar() {
        List<String> linhas = new ArrayList<>();
        try {
            linhas = Files.readAllLines(config.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            jogo.aviso("Não consegui ler o config.yml: " + e.getMessage());
        }
        String chave = valor(linhas, "chave", "");
        String origem = "config.yml";
        String env = System.getenv("NEVERMISS_CHAVE");
        if (chave.isEmpty() && env != null && !env.trim().isEmpty()) {
            chave = env.trim();
            origem = "variável NEVERMISS_CHAVE";
        }
        String api = Nucleo.validarApi(valor(linhas, "api", Nucleo.API_PADRAO));
        String erro = null;
        if (api == null) erro = "O endereço em 'api' do config.yml precisa ser https.";
        else if (chave.isEmpty()) erro = "Sem chave. No console: nevermiss chave <chave do painel>";
        else if (!Nucleo.CHAVE.matcher(chave).matches()) erro = "Essa chave não é da NeverMiss (começa com nmmc_).";
        if (erro != null) {
            nucleo.configurar(api, null);
            nucleo.situacao = erro;
            jogo.aviso(erro);
            cancelar();
            return;
        }
        if (api.startsWith("http:")) jogo.aviso("API sem https em " + api + ": use só para teste.");
        nucleo.configurar(api, chave);
        // Sem isto o status seguia com o "Sem chave" até a primeira consulta.
        nucleo.situacao = "Ainda não consultou a NeverMiss";
        jogo.log("Chave ****" + chave.substring(chave.length() - 4) + " (" + origem + "). Primeira consulta em 5 s.");
        agendar(5);
    }

    void desligar() {
        ligado = false;
        cancelar();
        registro.fechar();
    }

    /** Um jogador entrou: se há entrega esperando por ele, adianta a consulta. */
    void entrou(String nick) {
        if (nucleo.configurado() && nucleo.aoEntrar(nick)) adiantar();
    }

    /** /nevermiss. console = quem mandou é o console (só ele troca a chave). O responder pode ser chamado de outra thread. */
    void comando(String[] args, boolean console, Consumer<String> responder) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status":
                for (String linha : nucleo.status()) responder.accept(linha);
                return;
            case "testar":
                if (!nucleo.configurado()) {
                    responder.accept("[NeverMiss] " + nucleo.situacao);
                    return;
                }
                pediuTeste = responder;
                responder.accept("[NeverMiss] Consultando a NeverMiss...");
                adiantar();
                return;
            case "recarregar":
                configurar();
                responder.accept("[NeverMiss] Configuração recarregada. " + nucleo.situacao);
                return;
            case "chave":
                if (!console) {
                    responder.accept("[NeverMiss] A chave só pode ser trocada no console do servidor.");
                    return;
                }
                if (args.length != 2 || !Nucleo.CHAVE.matcher(args[1]).matches()) {
                    responder.accept("[NeverMiss] Use: nevermiss chave <chave do painel, começa com nmmc_>");
                    return;
                }
                try {
                    gravarChave(args[1]);
                } catch (IOException e) {
                    responder.accept("[NeverMiss] Não consegui gravar o config.yml: " + e.getMessage());
                    return;
                }
                configurar();
                return;
            default:
                responder.accept("[NeverMiss] Use: /nevermiss status | testar | recarregar");
        }
    }

    private synchronized void cancelar() {
        if (proxima != null) proxima.run();
        proxima = null;
    }

    private synchronized void agendar(long segundos) {
        cancelar();
        if (ligado) proxima = jogo.depois(Math.max(1, segundos), this::volta);
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
            jogo.aviso("Erro na consulta à NeverMiss: " + e);
            segundos = 60;
        } finally {
            emCiclo.set(false);
        }
        Consumer<String> quem = pediuTeste;
        pediuTeste = null;
        if (quem != null && ligado) quem.accept("[NeverMiss] " + nucleo.situacao);
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

    /** O valor de "nome: valor" no config.yml, com ou sem aspas. */
    static String valor(List<String> linhas, String nome, String padrao) {
        for (String l : linhas) {
            if (!l.startsWith(nome + ":")) continue;
            String v = l.substring(nome.length() + 1).trim();
            if (!v.isEmpty() && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
                int fim = v.indexOf(v.charAt(0), 1);
                return fim < 0 ? v.substring(1) : v.substring(1, fim);
            }
            int comentario = v.indexOf(" #");
            return (comentario < 0 ? v : v.substring(0, comentario)).trim();
        }
        return padrao;
    }

    /** Troca só a linha da chave: os comentários do config.yml ficam. */
    private void gravarChave(String chave) throws IOException {
        List<String> linhas = config.exists() ? new ArrayList<>(Files.readAllLines(config.toPath(), StandardCharsets.UTF_8)) : new ArrayList<>();
        String nova = "chave: \"" + chave + "\"";
        int i = 0;
        while (i < linhas.size() && !linhas.get(i).startsWith("chave:")) i++;
        if (i < linhas.size()) linhas.set(i, nova);
        else linhas.add(nova);
        File tmp = new File(config.getPath() + ".tmp");
        Files.write(tmp.toPath(), linhas, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), config.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
