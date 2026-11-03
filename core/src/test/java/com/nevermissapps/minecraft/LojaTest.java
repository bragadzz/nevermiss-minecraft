package com.nevermissapps.minecraft;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A /loja do jogo (contrato, 18) contra a API falsa: o catálogo, o Pix com o QR, a consulta e as recusas. */
public class LojaTest {
    private static final String CHAVE = "nmmc_" + new String(new char[43]).replace('\0', 'b');

    @Rule
    public TemporaryFolder pasta = new TemporaryFolder();

    private ApiFalsa api;
    private Loja loja;

    @Before
    public void antes() throws IOException {
        api = new ApiFalsa(0, CHAVE);
        Nucleo n = new Nucleo(new NucleoTest.JogoFalso(), new Registro(new File(pasta.getRoot(), "executadas.txt")), new Resgates(new File(pasta.getRoot(), "resgates.txt")), "1.3.0");
        n.configurar(api.url(), CHAVE);
        loja = new Loja(n);
    }

    @After
    public void depois() {
        api.close();
    }

    @Test
    public void catalogoGuardado15Segundos() throws Exception {
        Loja.Catalogo c = loja.catalogo();
        assertTrue(c.aberta);
        assertEquals("Teste", c.nome);
        assertEquals(2, c.produtos.size());
        Loja.Produto vip = c.produtos.get(0);
        assertEquals("VIP Ouro", vip.nome);
        assertEquals(Arrays.asList("Tag dourada, kit diario e /fly no", "lobby por 30 dias."), vip.descricao);
        Loja.Variacao v = vip.variacoes.get(0);
        assertEquals(19.9, v.preco, 0.001);
        assertEquals(5, v.max);
        assertEquals(-1, v.restam);
        assertEquals("gold_ingot", v.icone.item);
        // 10% a partir de 3, como o brutoDaLinha da API
        assertEquals(39.8, v.total(2), 0.001);
        assertEquals(53.73, v.total(3), 0.001);
        Loja.Produto chaves = c.produtos.get(1);
        assertTrue(chaves.aPartirDe());
        assertFalse(chaves.esgotado());
        assertEquals(4.5, chaves.menor(), 0.001);
        assertSame(c, loja.catalogo());
    }

    @Test
    public void pixComQrPagoDepois() throws Exception {
        Loja.Catalogo c = loja.catalogo();
        Loja.Pedido p = loja.comprar(c.produtos.get(0), c.produtos.get(0).variacoes.get(0), 2, "Steve", false, "chave-de-teste-1");
        assertEquals("aguardando", p.status);
        assertEquals(39.8, p.total, 0.001);
        assertEquals(ApiFalsa.COPIA_COLA, p.copiaCola);
        assertEquals(49, p.tamanho);
        // Os cantos do QR: o quadrado escuro do localizador, a borda clara depois dele
        assertTrue(p.modulo(0, 0));
        assertTrue(p.modulo(6, 6));
        assertFalse(p.modulo(7, 7));
        assertTrue(p.modulo(0, 48));
        assertTrue(System.currentTimeMillis() < p.expiraEm);
        // O mesmo clique de novo: o mesmo pedido
        assertEquals(p.numero, loja.comprar(c.produtos.get(0), c.produtos.get(0).variacoes.get(0), 2, "Steve", false, "chave-de-teste-1").numero);
        api.pedidosLoja.get(p.numero).addProperty("status", "pago");
        Loja.Pedido pago = loja.status(p.numero);
        assertEquals("pago", pago.status);
        assertEquals(0, pago.tamanho);
    }

    @Test
    public void recusas() throws Exception {
        api.catalogo.addProperty("aberta", false);
        api.catalogo.addProperty("motivo", "A loja ainda não aceita Pix.");
        Loja.Catalogo c = loja.catalogo();
        assertFalse(c.aberta);
        assertEquals("A loja ainda não aceita Pix.", c.motivo);
        try {
            loja.status("T99999");
            fail();
        } catch (Loja.Erro e) {
            assertEquals("Pedido não encontrado", e.getMessage());
        }
        try {
            loja.status("../../fila");
            fail();
        } catch (Loja.Erro e) {
            assertEquals("Pedido não encontrado.", e.getMessage());
        }
        Nucleo fora = new Nucleo(new NucleoTest.JogoFalso(), new Registro(new File(pasta.getRoot(), "e2.txt")), new Resgates(new File(pasta.getRoot(), "r2.txt")), "1.3.0");
        fora.configurar("http://127.0.0.1:1", CHAVE);
        try {
            new Loja(fora).status("T00001");
            fail();
        } catch (Loja.Erro e) {
            assertEquals(Loja.FORA, e.getMessage());
        }
    }

    @Test
    public void textos() {
        assertEquals("R$ 1.234,50", Loja.reais(1234.5));
        assertEquals("VIP sem cor", Loja.limpo("VIP§a sem\ncor", 60, null));
        assertEquals(4, Loja.linhas("uma descrição bem comprida que passa de quatro linhas de trinta e quatro letras cada uma delas, para ver o corte no fim do texto").size());
    }
}
