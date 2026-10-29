package com.nevermissapps.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** O que o plugin confirma para a API sobre uma entrega. */
final class Resultado {
    final String id;
    final String estado;
    final String motivo;
    final boolean[] comandos;

    private Resultado(String id, String estado, String motivo, boolean[] comandos) {
        this.id = id;
        this.estado = estado;
        this.motivo = motivo;
        this.comandos = comandos;
    }

    static Resultado entregue(String id, boolean[] comandos) {
        return new Resultado(id, "entregue", null, comandos);
    }

    static Resultado falhou(String id, String motivo, boolean[] comandos) {
        return new Resultado(id, "falhou", motivo, comandos);
    }

    /** O jogador não está online: volta para a fila sem contar como falha. */
    static Resultado adiada(String id) {
        return new Resultado(id, "adiada", null, null);
    }

    JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("estado", estado);
        if (motivo != null) o.addProperty("motivo", motivo);
        if (comandos != null) {
            JsonArray a = new JsonArray();
            for (boolean ok : comandos) a.add(new JsonPrimitive(ok));
            o.add("comandos", a);
        }
        return o;
    }

    /** Linha do executadas.txt: "<id> feita <ms> <estado> <motivo|-> <1 e 0 por comando|->". */
    String linha(long em) {
        StringBuilder bits = new StringBuilder();
        if (comandos != null) for (boolean ok : comandos) bits.append(ok ? '1' : '0');
        return id + " feita " + em + " " + estado + " " + (motivo == null ? "-" : motivo) + " "
                + (bits.length() == 0 ? "-" : bits);
    }

    static Resultado deLinha(String[] p) {
        if (p.length < 6) return null;
        boolean[] ok = new boolean[p[5].equals("-") ? 0 : p[5].length()];
        for (int i = 0; i < ok.length; i++) ok[i] = p[5].charAt(i) == '1';
        return new Resultado(p[0], p[3], p[4].equals("-") ? null : p[4], ok);
    }
}
