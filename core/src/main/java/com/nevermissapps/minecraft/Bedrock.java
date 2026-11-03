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
    // O gamertag como aparece no Minecraft: 1 a 16 letras, números e espaço, começa com letra, sem espaço duplo nem nas pontas.
    // Sem o "#1234" do gamertag novo: sem ele, "Cool Guy#1234" e "Cool Guy" dariam o mesmo nome (a entrega cairia em outra conta).
    static final Pattern GAMERTAG = Pattern.compile("^(?=.{1,16}$)[A-Za-z][A-Za-z0-9]*(?: [A-Za-z0-9]+)*$");

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

    /** O nome do jogador no servidor. O gamertag já passou pelo {@link #GAMERTAG}. */
    String nome(String gamertag) {
        String n = (prefixo + gamertag).replace(' ', '_');
        return n.length() > 16 ? n.substring(0, 16) : n;
    }

    /** O UUID que o Floodgate dá ao jogador offline (vem do xuid). Consulta a GeyserMC: nunca na thread principal. */
    UUID uuid(String gamertag) throws Exception {
        if (uuidPara == null) return null;
        Future<?> f = (Future<?>) uuidPara.invoke(api, gamertag);
        return (UUID) f.get(10, TimeUnit.SECONDS);
    }

    /** O gamertag do jogador online que entrou pelo Geyser (a /loja compra no nome dele). null = jogador Java. */
    String gamertag(UUID jogador) {
        if (api == null) return null;
        try {
            // Pela interface: a classe que implementa pode não ser pública
            ClassLoader l = api.getClass().getClassLoader();
            Object p = Class.forName("org.geysermc.floodgate.api.FloodgateApi", false, l).getMethod("getPlayer", UUID.class).invoke(api, jogador);
            return p == null ? null : String.valueOf(Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer", false, l).getMethod("getUsername").invoke(p));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
