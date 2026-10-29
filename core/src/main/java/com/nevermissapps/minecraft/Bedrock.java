package com.nevermissapps.minecraft;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Jogadores Bedrock que entram pelo Geyser + Floodgate. O Floodgate dá a cada um um nome no servidor:
 * o prefixo (padrão ".") mais o gamertag, com os espaços trocados por "_", cortado em 16.
 * A API do Floodgate é a mesma no Bukkit, no Velocity e no BungeeCord, e vai por reflexão (o jar não depende dela).
 */
class Bedrock {
    // 1 a 16 letras, números e espaço, começa com letra, sem espaço duplo nem nas pontas. O gamertag novo vem com "#1234".
    static final Pattern GAMERTAG = Pattern.compile("^(?=[^#]{1,16}(?:#|$))[A-Za-z][A-Za-z0-9]*(?: [A-Za-z0-9]+)*(?:#[0-9]{1,4})?$");

    final String prefixo;
    private final Object api;
    private final Method uuidPara;

    Bedrock(String prefixo) {
        this(prefixo, null, null);
    }

    private Bedrock(String prefixo, Object api, Method uuidPara) {
        this.prefixo = prefixo;
        this.api = api;
        this.uuidPara = uuidPara;
    }

    /** null quando o Floodgate não está aqui. O loader é o do plugin, que enxerga os outros plugins. */
    static Bedrock detectar(ClassLoader loader) {
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, loader);
            Object api = c.getMethod("getInstance").invoke(null);
            if (api == null) return null;
            Object prefixo = c.getMethod("getPlayerPrefix").invoke(api);
            Method uuidPara = null;
            try {
                uuidPara = c.getMethod("getUuidFor", String.class);
            } catch (NoSuchMethodException antigo) {
                // Floodgate 2.0: sem a busca pelo gamertag, o {uuid} só sai com o jogador online
            }
            return new Bedrock(prefixo == null ? "" : prefixo.toString(), api, uuidPara);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    static String semSufixo(String gamertag) {
        int h = gamertag.indexOf('#');
        return h < 0 ? gamertag : gamertag.substring(0, h);
    }

    /** O nome do jogador no servidor. O gamertag já passou pelo {@link #GAMERTAG}. */
    String nome(String gamertag) {
        String n = (prefixo + semSufixo(gamertag)).replace(' ', '_');
        return n.length() > 16 ? n.substring(0, 16) : n;
    }

    /** O UUID que o Floodgate dá ao jogador (vem do xuid). Pode consultar a GeyserMC: nunca na thread principal. */
    UUID uuid(String gamertag) throws Exception {
        if (uuidPara == null) return null;
        Future<?> f = (Future<?>) uuidPara.invoke(api, semSufixo(gamertag));
        return (UUID) f.get(10, TimeUnit.SECONDS);
    }
}
