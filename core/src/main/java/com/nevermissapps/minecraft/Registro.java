package com.nevermissapps.minecraft;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * plugins/NeverMiss/executadas.txt: os ids que já rodaram neste servidor.
 * É o que garante que uma entrega nunca roda 2 vezes, mesmo que a confirmação se perca.
 */
final class Registro {
    private static final long GUARDAR_MS = 30L * 24 * 60 * 60 * 1000;

    private final Set<String> rodando = new HashSet<>();
    private final Map<String, Resultado> feitas = new HashMap<>();
    private final FileOutputStream saida;

    Registro(File arquivo) throws IOException {
        long limite = System.currentTimeMillis() - GUARDAR_MS;
        List<String> manter = new ArrayList<>();
        if (arquivo.exists()) {
            for (String linha : Files.readAllLines(arquivo.toPath(), StandardCharsets.UTF_8)) {
                String[] p = linha.trim().split(" ");
                if (p.length < 3) continue;
                long em;
                try {
                    em = Long.parseLong(p[2]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (em < limite) continue;
                if (p[1].equals("rodando")) {
                    rodando.add(p[0]);
                } else if (p[1].equals("feita") && p.length >= 6) {
                    feitas.put(p[0], Resultado.deLinha(p));
                } else {
                    continue; // linha cortada por uma queda: o "rodando" só é gravado inteiro antes de rodar
                }
                manter.add(linha.trim());
            }
        }
        File pasta = arquivo.getAbsoluteFile().getParentFile();
        if (!pasta.isDirectory() && !pasta.mkdirs()) throw new IOException("não consegui criar " + pasta);
        File tmp = new File(pasta, arquivo.getName() + ".tmp");
        Files.write(tmp.toPath(), manter, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), arquivo.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        saida = new FileOutputStream(arquivo, true);
    }

    synchronized Resultado feita(String id) {
        return feitas.get(id);
    }

    /** Começou a rodar e não terminou: o servidor caiu no meio. */
    synchronized boolean interrompida(String id) {
        return rodando.contains(id) && !feitas.containsKey(id);
    }

    synchronized List<String> interrompidas() {
        List<String> ids = new ArrayList<>();
        for (String id : rodando) if (!feitas.containsKey(id)) ids.add(id);
        return ids;
    }

    /** Vai para o disco (com sync) antes do primeiro comando rodar. */
    synchronized void marcarRodando(String id) throws IOException {
        gravar(id + " rodando " + System.currentTimeMillis());
        rodando.add(id);
    }

    synchronized void marcarFeita(Resultado r) throws IOException {
        feitas.put(r.id, r);
        gravar(r.linha(System.currentTimeMillis()));
    }

    synchronized void fechar() {
        try {
            saida.close();
        } catch (IOException ignorado) {
            // fechando o servidor: nada a fazer
        }
    }

    private void gravar(String linha) throws IOException {
        saida.write((linha + "\n").getBytes(StandardCharsets.UTF_8));
        saida.flush();
        saida.getFD().sync();
    }
}
