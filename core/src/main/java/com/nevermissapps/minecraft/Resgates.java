package com.nevermissapps.minecraft;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * plugins/NeverMiss/resgates.txt: "<id do direito> <epoch ms>", a hora do último /kit de cada direito.
 * A API não guarda o resgate: é este arquivo que segura o intervalo. Vai para o disco antes de dar o kit.
 */
final class Resgates {
    // O intervalo vai até 1 ano: um resgate mais velho que isso não segura mais nada.
    private static final long GUARDAR_MS = 366L * 24 * 60 * 60 * 1000;

    private final Map<String, Long> ultimos = new HashMap<>();
    private final FileOutputStream saida;

    Resgates(File arquivo) throws IOException {
        long limite = System.currentTimeMillis() - GUARDAR_MS;
        if (arquivo.exists()) {
            for (String linha : Files.readAllLines(arquivo.toPath(), StandardCharsets.UTF_8)) {
                String[] p = linha.trim().split(" ");
                try {
                    long em = p.length == 2 ? Long.parseLong(p[1]) : 0;
                    if (em >= limite && em > ultimo(p[0])) ultimos.put(p[0], em);
                } catch (NumberFormatException cortada) {
                    // linha cortada por uma queda
                }
            }
        }
        List<String> manter = new ArrayList<>();
        for (Map.Entry<String, Long> e : ultimos.entrySet()) manter.add(e.getKey() + " " + e.getValue());
        File pasta = arquivo.getAbsoluteFile().getParentFile();
        if (!pasta.isDirectory() && !pasta.mkdirs()) throw new IOException("não consegui criar " + pasta);
        File tmp = new File(pasta, arquivo.getName() + ".tmp");
        Files.write(tmp.toPath(), manter, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), arquivo.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        saida = new FileOutputStream(arquivo, true);
    }

    /** 0 = nunca resgatou. */
    synchronized long ultimo(String id) {
        Long em = ultimos.get(id);
        return em == null ? 0 : em;
    }

    synchronized void marcar(String id, long em) throws IOException {
        saida.write((id + " " + em + "\n").getBytes(StandardCharsets.UTF_8));
        saida.flush();
        saida.getFD().sync();
        ultimos.put(id, em);
    }

    synchronized void fechar() {
        try {
            saida.close();
        } catch (IOException ignorado) {
            // fechando o servidor: nada a fazer
        }
    }
}
