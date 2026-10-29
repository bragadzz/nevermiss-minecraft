package com.nevermissapps.minecraft;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NucleoTest {
    private static final String CHAVE = "nmmc_" + repetir('a', 43);

    @Rule
    public TemporaryFolder pasta = new TemporaryFolder();

    private ApiFalsa api;
    private JogoFalso jogo;
    private File arquivo;

    @Before
    public void antes() throws IOException {
        api = new ApiFalsa(0, CHAVE);
        jogo = new JogoFalso();
        arquivo = new File(pasta.getRoot(), "NeverMiss/executadas.txt");
    }

    @After
    public void depois() {
        api.close();
    }

    private Nucleo nucleo() throws IOException {
        Nucleo n = new Nucleo(jogo, new Registro(arquivo), "1.0.0");
        n.configurar(api.url(), CHAVE);
        return n;
    }

    @Test
    public void rodaUmaVezEConfirma() throws IOException {
        api.adicionar("mc1", "Steve", false, "scoreboard players add Steve vip 1", "/say oi");
        Nucleo n = nucleo();
        assertEquals(30, n.ciclo());
        assertEquals(Arrays.asList("scoreboard players add Steve vip 1", "say oi"), jogo.rodou);
        assertEquals("entregue", api.entrega("mc1").estado);
        assertEquals(0, n.aConfirmar());
        n.ciclo();
        assertEquals(2, jogo.rodou.size());
        assertTrue(jogo.log.contains("Rodou: say oi"));
    }

    @Test
    public void confirmacaoPerdidaNaoRodaDeNovo() throws IOException {
        api.leaseMs = 0;
        api.cortar = 2;
        api.adicionar("mc1", "Steve", false, "scoreboard players add Steve vip 1");
        Nucleo n = nucleo();
        n.ciclo();
        assertEquals(1, n.aConfirmar());
        assertEquals("entregando", api.entrega("mc1").estado);
        // A confirmação guardada é cortada de novo, a entrega volta pelo lease vencido e o arquivo segura.
        n.ciclo();
        assertEquals(1, jogo.rodou.size());
        assertEquals("entregue", api.entrega("mc1").estado);
        assertEquals(2, api.entrega("mc1").tentativas);
    }

    @Test
    public void reiniciarNoMeioDaConfirmacaoNaoRodaDeNovo() throws IOException {
        api.leaseMs = 0;
        api.cortar = 1;
        api.adicionar("mc1", "Steve", false, "say oi");
        nucleo().ciclo();
        // Servidor reiniciou: a fila em memória sumiu, sobra o executadas.txt.
        Nucleo depois = nucleo();
        assertEquals(0, depois.aConfirmar());
        depois.ciclo();
        assertEquals(1, jogo.rodou.size());
        assertEquals("entregue", api.entrega("mc1").estado);
    }

    @Test
    public void servidorCaiuNoMeioViraInterrompida() throws IOException {
        Files.createDirectories(arquivo.getParentFile().toPath());
        Files.write(arquivo.toPath(), ("mc2 rodando " + System.currentTimeMillis() + "\n").getBytes(StandardCharsets.UTF_8));
        api.adicionar("mc2", "Steve", false, "say oi").estado = "entregando";
        api.adicionar("mc3", "Steve", false, "say tchau");
        Nucleo n = nucleo();
        assertEquals(1, n.aConfirmar());
        n.ciclo();
        assertEquals("falhou", api.entrega("mc2").estado);
        assertEquals("interrompida", api.entrega("mc2").motivo);
        assertEquals(Collections.singletonList("say tchau"), jogo.rodou);
        // Reabrir não cria outra confirmação: o "feita" já está no arquivo.
        assertEquals(0, nucleo().aConfirmar());
    }

    @Test
    public void soOnlineEsperaOJogador() throws IOException {
        api.adicionar("mc4", "Alex", true, "give Alex diamond 1");
        Nucleo n = nucleo();
        assertEquals(30, n.ciclo());
        assertTrue(jogo.rodou.isEmpty());
        assertEquals("pendente", api.entrega("mc4").estado);
        assertTrue(n.aoEntrar("alex"));
        assertFalse(n.aoEntrar("Steve"));

        jogo.online.add("Alex");
        n.ciclo();
        assertEquals("[\"Alex\"]", api.pulls.get(1).get("online").toString());
        assertEquals(Collections.singletonList("give Alex diamond 1"), jogo.rodou);
        assertEquals("entregue", api.entrega("mc4").estado);
    }

    @Test
    public void jogadorOnlineAoLigarAdiantaAConsulta() throws IOException {
        api.adicionar("mc4", "Alex", true, "give Alex diamond 1");
        jogo.online.add("Alex");
        Nucleo n = nucleo();
        assertEquals(5, n.ciclo());
        n.ciclo();
        assertEquals(1, jogo.rodou.size());
    }

    @Test
    public void jogadorSaiuAntesDeRodarFicaAdiada() throws IOException {
        api.adicionar("mc5", "Alex", true, "give Alex diamond 1");
        jogo.online.add("Alex");
        jogo.antesDaPrincipal = () -> jogo.online.remove("Alex");
        Nucleo n = nucleo();
        n.ciclo();
        n.ciclo();
        assertTrue(jogo.rodou.isEmpty());
        assertEquals("pendente", api.entrega("mc5").estado);
        assertEquals(0, api.entrega("mc5").tentativas);
    }

    @Test
    public void listaProibida() {
        for (String c : Arrays.asList("op Steve", "OP Steve", "/op Steve", "minecraft:op Steve", "deop x", "stop",
                "reload confirm", "rl", "ban x", "ban-ip 1.2.3.4", "whitelist off", "execute as @a run op @s",
                "essentials:sudo x op", "plugman unload x", "lp user x permission set *", "lp user x permission set essentials.*",
                "say oi\nop x", "", "   ", "say " + repetir('x', 260), "lp user x permission set \"*\" true",
                "lp user x permission set 'luckperms.*' true", "esudo Steve c:oi", "essentials:eban x", "tempban x 1d")) {
            assertTrue(c, Nucleo.bloqueado(c));
        }
        for (String c : Arrays.asList("lp user Steve parent addtemp vip 30d accumulate", "say op", "eco give Steve 100",
                "scoreboard players add Steve vip 1", "kit vip Steve", "give Steve diamond 1")) {
            assertFalse(c, Nucleo.bloqueado(c));
        }
    }

    @Test
    public void comandoProibidoNaoRodaNada() throws IOException {
        api.adicionar("mc6", "Steve", false, "say oi", "op Steve");
        nucleo().ciclo();
        assertTrue(jogo.rodou.isEmpty());
        assertEquals("falhou", api.entrega("mc6").estado);
        assertEquals("bloqueado", api.entrega("mc6").motivo);
    }

    @Test
    public void nickInvalidoNaoRoda() throws IOException {
        api.adicionar("mc7", "a b", false, "say oi");
        nucleo().ciclo();
        assertTrue(jogo.rodou.isEmpty());
        assertEquals("bloqueado", api.entrega("mc7").motivo);
    }

    @Test
    public void uuidEmServidorPirata() throws IOException {
        api.adicionar("mc8", "Steve", false, "lp user {uuid} parent add vip");
        nucleo().ciclo();
        assertTrue(jogo.rodou.isEmpty());
        assertEquals("uuid_pirata", api.entrega("mc8").motivo);
    }

    @Test
    public void uuidEmServidorOriginal() throws IOException {
        UUID steve = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        jogo.onlineMode = true;
        jogo.uuids.put("Steve", steve);
        api.adicionar("mc9", "Steve", false, "lp user {uuid} parent add vip");
        api.adicionar("mc10", "Ninguem_123", false, "lp user {uuid} parent add vip");
        nucleo().ciclo();
        assertEquals(Collections.singletonList("lp user " + steve + " parent add vip"), jogo.rodou);
        assertEquals("entregue", api.entrega("mc9").estado);
        assertEquals("nick_nao_existe", api.entrega("mc10").motivo);
    }

    @Test
    public void mojangForaDoArEErroNaoNickQueNaoExiste() throws IOException {
        jogo.onlineMode = true;
        jogo.fora = true;
        api.adicionar("mc29", "Steve", false, "lp user {uuid} parent add vip");
        nucleo().ciclo();
        assertTrue(jogo.rodou.isEmpty());
        assertEquals("erro", api.entrega("mc29").motivo);
    }

    @Test
    public void testeDoPainelNaoRodaNada() throws IOException {
        api.adicionar("mc11", null, false).evento = "teste";
        api.adicionar("mc12", null, false, "op x").evento = "teste";
        nucleo().ciclo();
        assertTrue(jogo.rodou.isEmpty());
        assertTrue(jogo.log.contains("Teste do painel recebido"));
        assertEquals("entregue", api.entrega("mc11").estado);
        assertEquals("entregue", api.entrega("mc12").estado);
    }

    @Test
    public void comandoQueFalha() throws IOException {
        jogo.falha.add("kit vip Steve");
        api.adicionar("mc13", "Steve", false, "say oi", "kit vip Steve");
        nucleo().ciclo();
        assertEquals(2, jogo.rodou.size());
        assertEquals("falhou", api.entrega("mc13").estado);
        assertEquals("comando_falhou", api.entrega("mc13").motivo);
        assertEquals("[true,false]", api.entrega("mc13").resultado.toString());
    }

    @Test
    public void errosDaApi() throws IOException {
        Nucleo n = nucleo();
        api.adicional = false;
        assertEquals(300, n.ciclo());
        api.adicional = true;
        api.minima = "9.0.0";
        assertEquals(600, n.ciclo());
        assertTrue(n.situacao.startsWith("Atualize o plugin"));
        api.minima = "1.0.0";
        api.intervaloMs = 5_000;
        assertEquals(30, n.ciclo());
        assertEquals(5, n.ciclo());
        api.intervaloMs = 0;
        n.configurar(api.url(), "nmmc_" + repetir('b', 43));
        assertEquals(300, n.ciclo());
        assertTrue(n.situacao.startsWith("Chave inválida"));
    }

    @Test
    public void apiForaDoArEsperaCadaVezMais() throws IOException {
        api.adicionar("mc14", "Steve", false, "say oi");
        Nucleo n = nucleo();
        String url = api.url();
        api.close();
        List<Integer> esperas = new ArrayList<>();
        for (int i = 0; i < 6; i++) esperas.add(n.ciclo());
        assertEquals(Arrays.asList(30, 60, 120, 240, 300, 300), esperas);
        assertTrue(n.situacao.startsWith("Sem resposta"));
        // Voltou: a fila anda.
        api = new ApiFalsa(Integer.parseInt(url.substring(url.lastIndexOf(':') + 1)), CHAVE);
        api.adicionar("mc14", "Steve", false, "say oi");
        assertEquals(30, n.ciclo());
        assertEquals("entregue", api.entrega("mc14").estado);
    }

    @Test
    public void pausaGeral() throws IOException {
        api.pausado = true;
        api.adicionar("mc15", "Steve", false, "say oi");
        Nucleo n = nucleo();
        n.ciclo();
        assertTrue(n.pausado);
        assertTrue(jogo.rodou.isEmpty());
        api.pausado = false;
        n.ciclo();
        assertEquals(1, jogo.rodou.size());
    }

    @Test
    public void mandaVersaoEModoDoServidor() throws IOException {
        nucleo().ciclo();
        String corpo = api.pulls.get(0).toString();
        assertTrue(corpo, corpo.contains("\"versao\":\"1.0.0\""));
        assertTrue(corpo, corpo.contains("\"onlineMode\":false"));
        assertTrue(corpo, corpo.contains("\"plataforma\":\"bukkit\",\"edicao\":\"java\",\"bedrock\":null"));
        jogo.bedrock = new BedrockFalso(".");
        jogo.plataforma = "velocity";
        nucleo().ciclo();
        corpo = api.pulls.get(1).toString();
        assertTrue(corpo, corpo.contains("\"plataforma\":\"velocity\",\"edicao\":\"java\",\"bedrock\":{\"floodgate\":true,\"prefixo\":\".\"}"));
    }

    @Test
    public void gamertag() {
        for (String g : Arrays.asList("Steve", "a", "Cool Guy", "Cool Guy 2", "x1234567890abcde", "Gamer1234")) {
            assertTrue(g, Bedrock.GAMERTAG.matcher(g).matches());
        }
        for (String g : Arrays.asList("", " a", "a ", "a  b", "1abc", "_abc", "a_b", "x1234567890abcdef", "a#", "a#12345",
                "a#x", "op;x", "a\nb", "Cool Guy#1234 ", "Gamer#1234", "Gamer 2#7")) {
            assertFalse(g, Bedrock.GAMERTAG.matcher(g).matches());
        }
        Bedrock b = new Bedrock(".");
        assertEquals(".Cool_Guy", b.nome("Cool Guy"));
        assertEquals(".x1234567890abcd", b.nome("x1234567890abcde"));
        assertEquals("BE_Cool_Guy", new Bedrock("BE_").nome("Cool Guy"));
        assertEquals("Steve", new Bedrock("").nome("Steve"));
    }

    @Test
    public void entregaBedrockTrocaONickPeloNomeNoServidor() throws IOException {
        jogo.bedrock = new BedrockFalso(".");
        api.adicionar("mc20", "Cool Guy", false, "give {nick} diamond 1", "say {nick} comprou").edicaoJogador = "bedrock";
        api.adicionar("mc21", "Steve", false, "give Steve diamond 1");
        nucleo().ciclo();
        assertEquals(Arrays.asList("give .Cool_Guy diamond 1", "say .Cool_Guy comprou", "give Steve diamond 1"), jogo.rodou);
        assertEquals("entregue", api.entrega("mc20").estado);
        assertEquals("entregue", api.entrega("mc21").estado);
    }

    @Test
    public void entregaBedrockSemFloodgateFalha() throws IOException {
        api.adicionar("mc22", "Cool Guy", false, "give {nick} diamond 1").edicaoJogador = "bedrock";
        nucleo().ciclo();
        assertTrue(jogo.rodou.isEmpty());
        assertEquals("sem_bedrock", api.entrega("mc22").motivo);
        assertTrue(jogo.log.contains("AVISO Entrega mc22 recusada: este servidor não aceita Bedrock: instale Geyser + Floodgate"));
    }

    @Test
    public void entregaBedrockConfereGamertagEComando() throws IOException {
        jogo.bedrock = new BedrockFalso("");
        api.adicionar("mc23", "a  b", false, "say {nick}").edicaoJogador = "bedrock";
        // O nome montado vira a 1ª palavra: a lista proibida vale para o comando já montado.
        api.adicionar("mc24", "op", false, "{nick} Steve").edicaoJogador = "bedrock";
        nucleo().ciclo();
        assertTrue(jogo.rodou.isEmpty());
        assertEquals("bloqueado", api.entrega("mc23").motivo);
        assertEquals("bloqueado", api.entrega("mc24").motivo);
    }

    @Test
    public void bedrockSoOnlineEsperaPeloNomeNoServidor() throws IOException {
        jogo.bedrock = new BedrockFalso(".");
        api.adicionar("mc25", "Cool Guy", true, "give {nick} diamond 1").edicaoJogador = "bedrock";
        Nucleo n = nucleo();
        n.ciclo();
        assertTrue(jogo.rodou.isEmpty());
        assertTrue(n.aoEntrar(".cool_guy"));
        assertFalse(n.aoEntrar("Cool_Guy"));

        jogo.online.add(".Cool_Guy");
        n.ciclo();
        assertEquals("[\"Cool Guy\"]", api.pulls.get(1).get("online").toString());
        assertEquals(Collections.singletonList("give .Cool_Guy diamond 1"), jogo.rodou);
        assertEquals("entregue", api.entrega("mc25").estado);
    }

    @Test
    public void uuidBedrockPeloFloodgate() throws IOException {
        UUID xuid = new UUID(0, 2535428650436000L);
        BedrockFalso b = new BedrockFalso(".");
        b.uuids.put("CoolGuy", xuid);
        b.uuids.put("Cool Guy", xuid);
        jogo.bedrock = b;
        jogo.onlineMode = true;
        api.adicionar("mc26", "CoolGuy", false, "lp user {uuid} parent add vip").edicaoJogador = "bedrock";
        api.adicionar("mc27", "Ninguem", false, "lp user {uuid} parent add vip").edicaoJogador = "bedrock";
        // Com espaço e offline o Floodgate buscaria outro nome: não busca, e é erro (não "não existe").
        api.adicionar("mc28", "Cool Guy", false, "lp user {uuid} parent add vip").edicaoJogador = "bedrock";
        nucleo().ciclo();
        assertEquals(Collections.singletonList("lp user " + xuid + " parent add vip"), jogo.rodou);
        assertEquals("nick_nao_existe", api.entrega("mc27").motivo);
        assertEquals("erro", api.entrega("mc28").motivo);
    }

    @Test
    public void proxyNaoTemComandoDeDesligar() {
        for (String c : Arrays.asList("end", "shutdown", "velocity:shutdown", "greload", "velocity reload", "velocity dump")) {
            assertTrue(c, Nucleo.bloqueado(c));
        }
        assertFalse(Nucleo.bloqueado("alert Steve comprou VIP"));
    }

    @Test
    public void configEComandos() throws IOException {
        File dir = new File(pasta.getRoot(), "Laco");
        Laco l = new Laco(jogo, dir, "1.1.0");
        File config = new File(dir, "config.yml");
        assertTrue(Files.readAllLines(config.toPath()).get(0).startsWith("# Chave"));
        l.configurar();
        assertTrue(l.nucleo.situacao.startsWith("Sem chave"));

        List<String> respostas = new ArrayList<>();
        l.comando(new String[]{"chave", CHAVE}, false, respostas::add);
        assertEquals("[NeverMiss] A chave só pode ser trocada no console do servidor.", respostas.get(0));
        l.comando(new String[]{"chave", CHAVE}, true, respostas::add);
        assertTrue(l.nucleo.configurado());
        assertEquals(1, jogo.agendadas.size());
        List<String> linhas = Files.readAllLines(config.toPath());
        assertTrue(linhas.get(0).startsWith("# Chave"));
        assertEquals(CHAVE, Laco.valor(linhas, "chave", ""));
        assertEquals(Nucleo.API_PADRAO, Laco.valor(linhas, "api", ""));

        // O config.yml que o Bukkit do 1.0.0 grava, sem aspas e sem os comentários.
        assertEquals("nmmc_x", Laco.valor(Arrays.asList("api: http://127.0.0.1:3409", "chave: nmmc_x # minha"), "chave", ""));
        assertEquals("http://127.0.0.1:3409", Laco.valor(Arrays.asList("api: 'http://127.0.0.1:3409'"), "api", ""));
        assertEquals("", Laco.valor(Arrays.asList("chave:"), "chave", "?"));

        respostas.clear();
        l.comando(new String[0], false, respostas::add);
        assertEquals("NeverMiss 1.1.0 (bukkit): Ainda não consultou a NeverMiss", respostas.get(0));
        assertEquals("Bedrock: não (instale Geyser + Floodgate)", respostas.get(4));
        l.desligar();
        assertEquals(1, jogo.canceladas);
    }

    @Test
    public void registroGuardaSo30Dias() throws IOException {
        long velho = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000;
        long novo = System.currentTimeMillis();
        Files.createDirectories(arquivo.getParentFile().toPath());
        Files.write(arquivo.toPath(), Arrays.asList(
                "velho rodando " + velho,
                "velho feita " + velho + " entregue - 1",
                "novo rodando " + novo,
                "novo feita " + novo + " falhou comando_falhou 10",
                "cortada rodan"), StandardCharsets.UTF_8);
        Registro r = new Registro(arquivo);
        assertNull(r.feita("velho"));
        assertEquals("comando_falhou", r.feita("novo").motivo);
        assertTrue(r.feita("novo").comandos[0]);
        assertFalse(r.feita("novo").comandos[1]);
        assertTrue(r.interrompidas().isEmpty());
        assertEquals(2, Files.readAllLines(arquivo.toPath()).size());
        r.fechar();
    }

    @Test
    public void validarApi() {
        assertEquals("https://api-website.nevermissapps.com", Nucleo.validarApi("https://api-website.nevermissapps.com/"));
        assertEquals("http://127.0.0.1:3409", Nucleo.validarApi("http://127.0.0.1:3409"));
        assertEquals("http://localhost", Nucleo.validarApi(" http://localhost "));
        assertNull(Nucleo.validarApi("http://api-website.nevermissapps.com"));
        assertNull(Nucleo.validarApi("ftp://x"));
        assertNull(Nucleo.validarApi("nada"));
        assertTrue(Nucleo.CHAVE.matcher(CHAVE).matches());
        assertFalse(Nucleo.CHAVE.matcher("nmmc_curta").matches());
    }

    private static String repetir(char c, int n) {
        return new String(new char[n]).replace('\0', c);
    }

    static final class BedrockFalso extends Bedrock {
        final Map<String, UUID> uuids = new HashMap<>();

        BedrockFalso(String prefixo) {
            super(prefixo);
        }

        @Override
        UUID uuid(String gamertag) {
            return uuids.get(gamertag);
        }
    }

    static final class JogoFalso implements Jogo {
        final List<String> rodou = new ArrayList<>();
        final List<Runnable> agendadas = new ArrayList<>();
        int canceladas;
        String plataforma = "bukkit";
        Bedrock bedrock;
        final Set<String> online = new HashSet<>();
        final Set<String> falha = new HashSet<>();
        final Map<String, UUID> uuids = new HashMap<>();
        final List<String> log = new ArrayList<>();
        boolean onlineMode;
        // A Mojang fora do ar (o uuid do proxy)
        boolean fora;
        Runnable antesDaPrincipal = () -> { };

        public boolean rodar(String comando) {
            rodou.add(comando);
            return !falha.contains(comando);
        }

        public boolean online(String nick) {
            for (String o : online) if (o.equalsIgnoreCase(nick)) return true;
            return false;
        }

        public UUID uuid(String nick) {
            if (fora) throw new java.io.UncheckedIOException(new IOException("a Mojang respondeu 429"));
            UUID u = uuids.get(nick);
            return u != null ? u : UUID.nameUUIDFromBytes(("OfflinePlayer:" + nick).getBytes(StandardCharsets.UTF_8));
        }

        public boolean onlineMode() {
            return onlineMode;
        }

        public String versao() {
            return "teste (MC: 1.8.8)";
        }

        public void log(String texto) {
            log.add(texto);
        }

        public void aviso(String texto) {
            log.add("AVISO " + texto);
        }

        public <T> T naPrincipal(Callable<T> tarefa) throws Exception {
            antesDaPrincipal.run();
            return tarefa.call();
        }

        public Runnable depois(long segundos, Runnable tarefa) {
            agendadas.add(tarefa);
            return () -> canceladas++;
        }

        public String plataforma() {
            return plataforma;
        }

        public Bedrock bedrock() {
            return bedrock;
        }
    }
}
