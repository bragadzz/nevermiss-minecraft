package com.nevermissapps.minecraft;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * O /loja no Spigot e no Paper (contrato, seção 18): um menu de baú com os produtos que o lojista criou no painel para este
 * servidor, a tela de quantidade e o Pix num mapa com o QR Code. A cada 5 s pergunta à NeverMiss se o Pix foi pago: pago, o
 * mapa some e a entrega chega pela fila de sempre. Não roda no Folia (o inventário teria que abrir na região do jogador).
 */
final class LojaMenu implements Listener {
    // O nome do mapa: é por ele que o plugin acha (e tira) o mapa do pedido, em qualquer versão
    private static final String MAPA = "§aPix · pedido #";
    private static final byte BRANCO = 34;
    private static final byte PRETO = 119;

    private final NeverMiss plugin;
    private final Loja loja;
    private final boolean moderno;
    // numero → o Pix aberto (o mapa na mão de alguém)
    private final Map<String, Aberto> abertos = new ConcurrentHashMap<>();
    private final Set<UUID> gerando = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> ultimoClique = new ConcurrentHashMap<>();
    private boolean conferindo;

    private static final class Aberto {
        final String numero;
        final UUID jogador;
        final long vence;
        // O item (volta depois da morte) e o desenho (apaga o QR quando fecha, onde quer que o mapa esteja)
        final ItemStack mapa;
        final Qr qr;
        // Para o aviso no chat de todos: o produto e a categoria que o [Ver na loja] abre
        final String produto;
        final String categoria;

        Aberto(String numero, UUID jogador, long vence, ItemStack mapa, Qr qr, String produto, String categoria) {
            this.numero = numero;
            this.jogador = jogador;
            this.vence = vence;
            this.mapa = mapa;
            this.qr = qr;
            this.produto = produto;
            this.categoria = categoria;
        }
    }

    /** O menu aberto: o que cada espaço faz. */
    private static final class Tela implements InventoryHolder {
        final Map<Integer, Runnable> acoes = new HashMap<>();
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    LojaMenu(NeverMiss plugin, Loja loja) {
        this.plugin = plugin;
        this.loja = loja;
        moderno = Kit.desde(plugin.versaoMc(), 1, 13, 0);
    }

    // --- /loja ---

    void abrir(Player p) {
        abrir(p, null);
    }

    /** categoria: o id do produto (o painel de venda) que já abre na vitrine, como o [Ver na loja] do aviso de compra. */
    void abrir(Player p, String categoria) {
        avisar(p, "§7Abrindo a loja...");
        assincrono(() -> {
            Loja.Catalogo c;
            try {
                c = loja.catalogo();
            } catch (Loja.Erro e) {
                principal(() -> avisar(p, "§c" + e.getMessage()));
                return;
            }
            principal(() -> {
                if (!p.isOnline()) return;
                if (!c.aberta) avisar(p, "§c" + (c.motivo != null ? c.motivo : "A loja está fechada agora."));
                else if (c.produtos.isEmpty()) {
                    avisar(p, "§7Nenhum produto à venda neste servidor agora.");
                    // O dono testando: por que cada produto do painel ficou fora (o jogador comum só lê a linha de cima)
                    if (!c.fora.isEmpty()) {
                        plugin.log("A /loja está vazia. Fora dela: " + String.join(" · ", c.fora));
                        if (p.hasPermission("nevermiss.admin")) for (String f : c.fora) avisar(p, "§8(op) §7" + f);
                    }
                } else {
                    int cat = 0;
                    for (int i = 0; i < c.produtos.size(); i++) if (c.produtos.get(i).id.equals(categoria)) cat = i;
                    vitrine(p, c, cat, 0);
                }
            });
        });
    }

    // A vitrine (baú de 6 linhas): em cima as categorias (os painéis de venda do bot), com o vidro verde embaixo da aberta e o "Como
    // comprar" no canto; no meio os produtos dela, 7 por linha em 2 linhas, longe das bordas; embaixo, as páginas
    private static final int[] VITRINE = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int ABAS = 7;

    private void vitrine(Player p, Loja.Catalogo c, int cat, int pagina) {
        int nCat = c.produtos.size();
        int abaInicio = (cat / ABAS) * ABAS;
        Loja.Produto pr = c.produtos.get(cat);
        Tela t = tela(6, "Loja: " + pr.nome);
        for (int i = 0; i < ABAS && abaInicio + i < nCat; i++) {
            int idx = abaInicio + i;
            Loja.Produto x = c.produtos.get(idx);
            boolean aberta = idx == cat;
            List<String> lore = new ArrayList<>();
            lore.add(x.esgotado() ? "§cEsgotado" : (x.aPartirDe() ? "§7a partir de " : "") + preco(x.menor()));
            lore.add("§7" + x.variacoes.size() + (x.variacoes.size() == 1 ? " produto" : " produtos"));
            lore.addAll(cinza(x.descricao));
            lore.add("");
            lore.add(aberta ? "§aAberta" : "§eClique para ver");
            por(t, 1 + i, item(x.icone, 1, (aberta ? "§a" : "§f") + x.nome, lore), aberta ? null : () -> vitrine(p, c, idx, 0));
            if (aberta) por(t, 10 + i, item(Kit.icone("lime_stained_glass_pane", "STAINED_GLASS_PANE:5"), 1, "§a" + x.nome, Collections.<String>emptyList()), null);
        }
        if (abaInicio > 0) por(t, 0, botao("arrow", "ARROW", "§fCategorias anteriores"), () -> vitrine(p, c, abaInicio - 1, 0));
        if (abaInicio + ABAS < nCat) por(t, 8, botao("arrow", "ARROW", "§fMais categorias"), () -> vitrine(p, c, abaInicio + ABAS, 0));
        else {
            por(t, 8, item(Kit.icone("book", "BOOK"), 1, "§eComo comprar", Arrays.asList("§7Escolha o produto e a quantidade", "§7e pague com Pix: o QR Code", "§7aparece num mapa na sua mão.")), null);
        }
        boolean bedrock = gamertag(p) != null;
        int paginas = Math.max(1, (pr.variacoes.size() + VITRINE.length - 1) / VITRINE.length);
        int pag = Math.max(0, Math.min(pagina, paginas - 1));
        for (int i = 0; i < VITRINE.length; i++) {
            int idx = pag * VITRINE.length + i;
            if (idx >= pr.variacoes.size()) break;
            Loja.Variacao v = pr.variacoes.get(idx);
            List<String> lore = new ArrayList<>();
            lore.add(v.esgotado ? "§cEsgotado" : preco(v.gratis ? 0 : v.preco) + de(v));
            if (v.restam >= 0 && !v.esgotado) lore.add("§7Restam " + v.restam);
            if (v.equipe) lore.add("§7A equipe entrega depois do pagamento");
            lore.addAll(cinza(v.descricao));
            lore.add("");
            lore.add(v.esgotado ? "§8Volte mais tarde" : bedrock && !v.bedrock ? "§cSó para o Minecraft Java" : "§eClique para comprar");
            por(t, VITRINE[i], item(v.icone, 1, (v.esgotado ? "§c" : "§a") + v.nome, lore), () -> confirmar(p, c, cat, v, v.min, pag));
        }
        if (paginas > 1) {
            if (pag > 0) por(t, 48, botao("arrow", "ARROW", "§fPágina anterior"), () -> vitrine(p, c, cat, pag - 1));
            por(t, 49, botao("paper", "PAPER", "§7Página " + (pag + 1) + " de " + paginas), null);
            if (pag < paginas - 1) por(t, 50, botao("arrow", "ARROW", "§fPróxima página"), () -> vitrine(p, c, cat, pag + 1));
        }
        p.openInventory(t.inv);
    }

    /** A quantidade e o botão de pagar. Mudar a quantidade monta a tela de novo. */
    private void confirmar(Player p, Loja.Catalogo c, int cat, Loja.Variacao v, int qtd, int pagina) {
        Loja.Produto pr = c.produtos.get(cat);
        if (v.esgotado) {
            avisar(p, "§c" + v.nome + " está esgotado.");
            return;
        }
        boolean bedrock = gamertag(p) != null;
        // "Categoria · Produto" só se couber nas 30 letras do título; senão, só o produto (cortado, a categoria some primeiro)
        String junto = pr.nome + " · " + v.nome;
        Tela t = tela(3, v.nome.equals(pr.nome) || junto.length() > 30 ? v.nome : junto);
        double total = v.total(qtd);
        List<String> lore = new ArrayList<>();
        lore.add("§7Preço: " + preco(v.gratis ? 0 : v.preco) + de(v));
        if (v.max > v.min) lore.add("§7Quantidade: §f" + qtd);
        if (v.descontoPct > 0 && v.max >= v.descontoAPartirDe && !v.gratis) {
            lore.add((qtd >= v.descontoAPartirDe ? "§a" : "§7") + Loja.limpo(String.format("%.0f", v.descontoPct), 4, "") + "% de desconto a partir de " + v.descontoAPartirDe);
        }
        lore.add("§7Total: " + preco(total));
        if (v.equipe) lore.add("§7A equipe entrega depois do pagamento");
        lore.addAll(cinza(v.descricao));
        por(t, 4, item(v.icone, Math.min(64, qtd), "§a" + v.nome, lore), null);
        if (v.max > v.min) {
            int[][] passos = {{10, -10}, {11, -1}, {15, 1}, {16, 10}};
            for (int[] s : passos) {
                int nova = Math.max(v.min, Math.min(v.max, qtd + s[1]));
                if (nova == qtd) continue;
                String cor = s[1] < 0 ? "red_stained_glass_pane" : "lime_stained_glass_pane";
                String antiga = s[1] < 0 ? "STAINED_GLASS_PANE:14" : "STAINED_GLASS_PANE:5";
                ItemStack b = item(Kit.icone(cor, antiga), Math.abs(s[1]), (s[1] < 0 ? "§c" : "§a") + (s[1] < 0 ? "" : "+") + s[1],
                        Collections.singletonList("§7Fica " + nova));
                por(t, s[0], b, () -> confirmar(p, c, cat, v, nova, pagina));
            }
        }
        por(t, 18, botao("arrow", "ARROW", "§fVoltar"), () -> vitrine(p, c, cat, pagina));
        if (bedrock && !v.bedrock) {
            por(t, 22, item(Kit.icone("barrier", "BARRIER"), 1, "§cSó para o Minecraft Java",
                    Collections.singletonList("§7Este produto não entrega para jogador Bedrock.")), null);
        } else {
            String rotulo = v.gratis ? "§aResgatar grátis" : "§aPagar " + preco(total) + " com Pix";
            List<String> como = v.gratis ? Collections.singletonList("§71 por jogador")
                    : Arrays.asList("§7O QR Code aparece num mapa", "§7na sua mão. Vence em 15 min.");
            por(t, 22, item(Kit.icone("emerald", "EMERALD"), 1, rotulo, como), () -> {
                p.closeInventory();
                pagar(p, pr, v, qtd, texto -> { });
            });
        }
        por(t, 26, botao("barrier", "BARRIER", "§cCancelar"), p::closeInventory);
        p.openInventory(t.inv);
    }

    /**
     * nevermiss comprar <jogador> <id>: o Pix de um produto direto, sem a vitrine (o "Pagar" do menu de outro plugin, pelo
     * console). O mesmo caminho do Pagar da confirmação, na quantidade mínima. responder: quem mandou o comando.
     */
    void comprar(Player p, String id, Consumer<String> responder) {
        assincrono(() -> {
            Loja.Produto pr;
            try {
                Loja.Catalogo c = loja.catalogo();
                if (!c.aberta) throw new Loja.Erro(c.motivo != null ? c.motivo : "A loja está fechada agora.");
                pr = Loja.escolher(c, id);
            } catch (Loja.Erro e) {
                principal(() -> recusar(p, responder, e.getMessage()));
                return;
            }
            principal(() -> {
                Loja.Variacao v = pr.variacoes.get(0);
                if (!p.isOnline()) responder.accept("[Loja] " + p.getName() + " saiu antes do Pix.");
                else if (v.esgotado) recusar(p, responder, v.nome + " está esgotado.");
                else if (gamertag(p) != null && !v.bedrock) recusar(p, responder, v.nome + " é só para o Minecraft Java.");
                else {
                    responder.accept("[Loja] " + (v.gratis ? "Resgatando " : "Gerando o Pix de ") + v.nome + " para " + p.getName() + ".");
                    p.closeInventory();
                    pagar(p, pr, v, v.min, responder);
                }
            });
        });
    }

    private static void recusar(Player p, Consumer<String> responder, String msg) {
        responder.accept("[Loja] " + msg);
        if (p.isOnline()) avisar(p, "§c" + msg);
    }

    /** eco: quem mandou o nevermiss comprar também lê a recusa da API (os 2 Pix abertos, o estoque). */
    private void pagar(Player p, Loja.Produto pr, Loja.Variacao v, int qtd, Consumer<String> eco) {
        if (!v.gratis && p.getInventory().firstEmpty() < 0) {
            avisar(p, "§cLibere um espaço no inventário para receber o mapa do Pix.");
            return;
        }
        String gamertag = gamertag(p);
        // O Floodgate só no proxy: o nome do jogador Bedrock aqui é ".Fulano", que não é nick nem gamertag
        if (gamertag == null && !Nucleo.NICK.matcher(p.getName()).matches()) {
            avisar(p, "§cA /loja não reconhece o seu nick neste servidor. Avise a equipe: o Floodgate tem que estar aqui também.");
            return;
        }
        if (!gerando.add(p.getUniqueId())) return;
        String nick = gamertag != null ? gamertag : p.getName();
        String chave = UUID.randomUUID().toString();
        String ip = p.getAddress() == null ? null : p.getAddress().getAddress().getHostAddress();
        avisar(p, v.gratis ? "§7Resgatando..." : "§7Gerando o Pix...");
        assincrono(() -> {
            Loja.Pedido ped;
            try {
                ped = comprar(pr, v, qtd, nick, gamertag != null, chave, ip);
            } catch (Loja.Erro e) {
                gerando.remove(p.getUniqueId());
                principal(() -> recusar(p, eco, e.getMessage()));
                return;
            }
            principal(() -> {
                gerando.remove(p.getUniqueId());
                // Saiu enquanto o Pix era gerado: sem o mapa, o Pix vence sozinho
                if (!p.isOnline()) return;
                if ("pago".equals(ped.status)) {
                    avisar(p, "§aResgatado! " + v.nome + (ped.equipe ? ": a equipe do servidor entrega em breve" : " chega em instantes") + " (pedido #" + ped.numero + ").");
                    for (String l : ped.mensagem) avisar(p, "§f" + l);
                    som(p);
                    comemorar(p, v.nome, pr.id, "resgatou");
                } else if ("aguardando".equals(ped.status)) {
                    entregarMapa(p, pr, v, ped);
                } else {
                    avisar(p, "§c" + (ped.aviso != null ? ped.aviso : "Este pedido não está mais aberto (pedido #" + ped.numero + ")."));
                }
            });
        });
    }

    /** A resposta perdida (a API criou o Pix, mas demorou): tenta de novo com a mesma chave, que devolve o mesmo pedido. */
    private Loja.Pedido comprar(Loja.Produto pr, Loja.Variacao v, int qtd, String nick, boolean bedrock, String chave, String ip) throws Loja.Erro {
        try {
            return loja.comprar(pr, v, qtd, nick, bedrock, chave, ip);
        } catch (Loja.Erro e) {
            if (!Loja.FORA.equals(e.getMessage())) throw e;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException i) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return loja.comprar(pr, v, qtd, nick, bedrock, chave, ip);
        }
    }

    private void entregarMapa(Player p, Loja.Produto pr, Loja.Variacao v, Loja.Pedido ped) {
        ItemStack mapa;
        Qr qr = new Qr(ped);
        try {
            mapa = mapa(p, ped, qr);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.aviso("A /loja não conseguiu montar o mapa do Pix: " + e);
            avisar(p, "§cNão deu para mostrar o QR Code aqui (pedido #" + ped.numero + "). Avise a equipe do servidor.");
            copiaECola(p, ped);
            return;
        }
        int mao = p.getInventory().getHeldItemSlot();
        ItemStack naMao = p.getInventory().getItem(mao);
        if (naMao == null || naMao.getType() == Material.AIR) p.getInventory().setItem(mao, mapa);
        else {
            int livre = -1;
            for (int i = 0; i < 9 && livre < 0; i++) {
                ItemStack x = p.getInventory().getItem(i);
                if (x == null || x.getType() == Material.AIR) livre = i;
            }
            if (livre >= 0) {
                p.getInventory().setItem(livre, mapa);
                p.getInventory().setHeldItemSlot(livre);
            } else {
                for (ItemStack sobra : p.getInventory().addItem(mapa).values()) p.getWorld().dropItem(p.getLocation(), sobra);
            }
        }
        long vence = ped.expiraEm > 0 ? ped.expiraEm : System.currentTimeMillis() + 15 * 60_000L;
        abertos.put(ped.numero, new Aberto(ped.numero, p.getUniqueId(), vence, mapa.clone(), qr, v.nome, pr.id));
        avisar(p, "§fPix de " + preco(ped.total) + " §fgerado: pedido §e#" + ped.numero + "§f.");
        avisar(p, "§7Segure o mapa e leia o QR Code com o app do banco. Vence em 15 min.");
        if (ped.recebedor != null) avisar(p, "§7Recebedor: §f" + ped.recebedor);
        copiaECola(p, ped);
        conferirDepois();
    }

    /** O copia e cola num clique (o jogo copia do 1.15 em diante); antes, só o QR do mapa. */
    private void copiaECola(Player p, Loja.Pedido ped) {
        if (ped.copiaCola == null || !Kit.desde(plugin.versaoMc(), 1, 15, 0) || gamertag(p) != null) return;
        try {
            TextComponent t = new TextComponent("§e§n[Copiar o Pix copia e cola]");
            t.setClickEvent(new ClickEvent(ClickEvent.Action.valueOf("COPY_TO_CLIPBOARD"), ped.copiaCola));
            t.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponent[]{new TextComponent("Cole no app do banco, em Pix copia e cola")}));
            p.spigot().sendMessage(t);
        } catch (RuntimeException | LinkageError semChat) {
            // servidor sem o chat do BungeeCord: fica o QR
        }
    }

    // --- O mapa ---

    @SuppressWarnings("deprecation")
    private ItemStack mapa(Player p, Loja.Pedido ped, Qr qr) throws ReflectiveOperationException {
        MapView view = Bukkit.createMap(p.getWorld());
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
        view.setScale(MapView.Scale.CLOSEST);
        // Um mapa novo (map_N.dat) por Pix: reaproveitar os ids só se algum servidor juntar milhares
        view.addRenderer(qr);
        // short no 1.8, int do 1.13 em diante: pela reflexão
        int id = ((Number) MapView.class.getMethod("getId").invoke(view)).intValue();
        ItemStack item;
        if (!moderno) item = new ItemStack(Material.MAP, 1, (short) id);
        else {
            item = new ItemStack(Material.matchMaterial("filled_map"));
            ItemMeta meta = item.getItemMeta();
            Class<?> mapMeta = Class.forName("org.bukkit.inventory.meta.MapMeta");
            try {
                mapMeta.getMethod("setMapView", MapView.class).invoke(meta, view);
            } catch (NoSuchMethodException so113) {
                mapMeta.getMethod("setMapId", int.class).invoke(meta, id);
            }
            item.setItemMeta(meta);
        }
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(MAPA + ped.numero);
        meta.setLore(Arrays.asList("§7Total: " + preco(ped.total), "§7Leia o QR Code com o app do banco"));
        item.setItemMeta(meta);
        return item;
    }

    /** O QR pintado uma vez (o canvas fica): branco, os módulos pretos de 2 pixels e a margem em volta. Fechado, fica em branco. */
    private static final class Qr extends MapRenderer {
        private final Loja.Pedido ped;
        private boolean feito;
        private volatile boolean fechado;
        private boolean limpo;

        Qr(Loja.Pedido ped) {
            super(false);
            this.ped = ped;
        }

        @SuppressWarnings("deprecation")
        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (fechado) {
                if (!limpo) for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) canvas.setPixel(x, y, BRANCO);
                limpo = true;
                return;
            }
            if (feito) return;
            feito = true;
            if (ped.imagem != null) {
                for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) canvas.setPixel(x, y, ped.imagem[y * 128 + x]);
                return;
            }
            int n = ped.tamanho;
            int px = Math.max(1, 128 / (n + 2));
            int margem = (128 - n * px) / 2;
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) canvas.setPixel(x, y, BRANCO);
            for (int l = 0; l < n; l++) {
                for (int col = 0; col < n; col++) {
                    if (!ped.modulo(l, col)) continue;
                    for (int dx = 0; dx < px; dx++) for (int dy = 0; dy < px; dy++) canvas.setPixel(margem + col * px + dx, margem + l * px + dy, PRETO);
                }
            }
        }
    }

    // --- O pagamento: a cada 5 s, fora da thread principal ---

    private synchronized void conferirDepois() {
        if (conferindo || abertos.isEmpty()) return;
        conferindo = true;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::conferir, 100);
    }

    private void conferir() {
        synchronized (this) {
            conferindo = false;
        }
        for (Aberto a : new ArrayList<>(abertos.values())) {
            Loja.Pedido ped;
            try {
                ped = loja.status(a.numero);
            } catch (Loja.Erro e) {
                // A NeverMiss fora: tenta na próxima. Muito depois de vencer, desiste e tira o mapa
                if (System.currentTimeMillis() > a.vence + 10 * 60_000L) principal(() -> fechar(a, "§cO Pix do pedido #" + a.numero + " venceu."));
                continue;
            }
            if ("pago".equals(ped.status)) {
                principal(() -> {
                    Player p = Bukkit.getPlayer(a.jogador);
                    fechar(a, "§aPagamento aprovado! Pedido #" + a.numero + (ped.equipe ? ": a equipe do servidor entrega em breve." : ": a entrega chega em instantes."));
                    if (p != null) for (String l : ped.mensagem) avisar(p, "§f" + l);
                    if (p != null) comemorar(p, a.produto, a.categoria, "comprou");
                });
            }
            else if ("vencido".equals(ped.status)) principal(() -> fechar(a, "§cO Pix do pedido #" + a.numero + " venceu. Abra a /loja para gerar outro."));
            else if (!"aguardando".equals(ped.status)) principal(() -> fechar(a, "§c" + (ped.aviso != null ? ped.aviso : "O pedido #" + a.numero + " foi cancelado.")));
            // A YuvexPay sem dizer que venceu: passado o prazo, o mapa sai (pago depois disso, a entrega chega igual)
            else if (System.currentTimeMillis() > a.vence + 60_000L) principal(() -> fechar(a, "§cO Pix do pedido #" + a.numero + " venceu. Abra a /loja para gerar outro."));
        }
        conferirDepois();
    }

    private void fechar(Aberto a, String msg) {
        if (abertos.remove(a.numero) == null) return;
        a.qr.fechado = true;
        // Offline: o mapa sai quando ele entrar (o pedido já não está em abertos)
        Player p = Bukkit.getPlayer(a.jogador);
        if (p == null) return;
        tirarMapas(p, Collections.singleton(a.numero));
        avisar(p, msg);
        if (msg.startsWith("§a")) som(p);
    }

    // --- A compra aprovada: fogos para quem comprou e o aviso no chat de todos, com o [Ver na loja] ---

    private final Set<UUID> fogos = ConcurrentHashMap.newKeySet();
    private static final Color[] CORES = {Color.LIME, Color.AQUA, Color.YELLOW, Color.FUCHSIA, Color.ORANGE, Color.WHITE};

    private void comemorar(Player p, String produto, String categoria, String verbo) {
        titulo(p, "§aPagamento aprovado!", "§f" + produto);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 3; i++) {
            final int n = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                Firework f = p.getWorld().spawn(p.getLocation().add(r.nextDouble() * 4 - 2, 1, r.nextDouble() * 4 - 2), Firework.class);
                FireworkMeta meta = f.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(n == 1 ? FireworkEffect.Type.STAR : FireworkEffect.Type.BALL_LARGE)
                        .withColor(CORES[r.nextInt(CORES.length)], CORES[r.nextInt(CORES.length)]).withFade(Color.WHITE).flicker(true).trail(true).build());
                meta.setPower(0);
                f.setFireworkMeta(meta);
                fogos.add(f.getUniqueId());
                Bukkit.getScheduler().runTaskLater(plugin, () -> fogos.remove(f.getUniqueId()), 100);
            }, i * 12L);
        }
        String nome = p.getName();
        String texto = "§6§l★ §e" + nome + " §7" + verbo + " §a§l" + produto + " §7na /loja! ";
        try {
            TextComponent linha = new TextComponent(texto);
            TextComponent ver = new TextComponent("§b§n[Ver na loja]");
            ver.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/nmloja " + categoria));
            ver.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponent[]{new TextComponent("Abrir a /loja em " + produto)}));
            linha.addExtra(ver);
            for (Player x : NeverMiss.jogadores()) x.spigot().sendMessage(linha);
        } catch (RuntimeException | LinkageError semChat) {
            for (Player x : NeverMiss.jogadores()) x.sendMessage(texto + "§7(/loja)");
        }
    }

    /** Os fogos da compra não machucam ninguém. */
    @EventHandler
    public void fogos(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework && fogos.contains(e.getDamager().getUniqueId())) e.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    private static void titulo(Player p, String titulo, String sub) {
        try {
            p.sendTitle(titulo, sub);
        } catch (RuntimeException | LinkageError semTitulo) {
            // servidor sem título (1.7.10): fica o chat
        }
    }

    /** Entrou: os mapas de pedido que acabaram (ou que ficaram de antes de o servidor reiniciar) saem. */
    @EventHandler
    public void entrou(PlayerJoinEvent e) {
        tirarMapas(e.getPlayer(), null);
    }

    private static boolean ehMapa(ItemStack x) {
        return x != null && x.hasItemMeta() && x.getItemMeta().hasDisplayName() && x.getItemMeta().getDisplayName().startsWith(MAPA);
    }

    // --- O mapa fica com o jogador: não cai, não vai para baú nem para moldura; morrendo, volta no respawn ---

    @EventHandler
    public void largou(PlayerDropItemEvent e) {
        if (!ehMapa(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        avisar(e.getPlayer(), "§7O mapa do Pix fica com você até pagar ou vencer.");
    }

    @EventHandler
    public void guardou(InventoryClickEvent e) {
        InventoryType tipo = e.getView().getTopInventory().getType();
        if (e.getInventory().getHolder() instanceof Tela || tipo == InventoryType.CRAFTING || tipo == InventoryType.CREATIVE) return;
        ItemStack atalho = e.getHotbarButton() >= 0 ? e.getWhoClicked().getInventory().getItem(e.getHotbarButton()) : null;
        if (ehMapa(e.getCurrentItem()) || ehMapa(e.getCursor()) || ehMapa(atalho)) e.setCancelled(true);
    }

    @EventHandler
    public void arrastouMapa(InventoryDragEvent e) {
        InventoryType tipo = e.getView().getTopInventory().getType();
        if (tipo != InventoryType.CRAFTING && tipo != InventoryType.CREATIVE && ehMapa(e.getOldCursor())) e.setCancelled(true);
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void moldura(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof ItemFrame && ehMapa(e.getPlayer().getItemInHand())) e.setCancelled(true);
    }

    @EventHandler
    public void morreu(PlayerDeathEvent e) {
        e.getDrops().removeIf(LojaMenu::ehMapa);
    }

    @EventHandler
    public void voltou(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        for (Aberto a : abertos.values()) {
            if (!a.jogador.equals(p.getUniqueId())) continue;
            boolean tem = false;
            for (ItemStack x : p.getInventory().getContents()) tem |= ehMapa(x) && x.getItemMeta().getDisplayName().equals(MAPA + a.numero);
            if (!tem) p.getInventory().addItem(a.mapa.clone());
        }
    }

    /** numeros null = todo mapa de pedido que não está aberto. */
    private void tirarMapas(Player p, Set<String> numeros) {
        ItemStack[] tudo = p.getInventory().getContents();
        for (int i = 0; i < tudo.length; i++) {
            ItemStack x = tudo[i];
            if (x == null || !x.hasItemMeta() || !x.getItemMeta().hasDisplayName()) continue;
            String nome = x.getItemMeta().getDisplayName();
            if (!nome.startsWith(MAPA)) continue;
            String numero = nome.substring(MAPA.length());
            if (numeros != null ? numeros.contains(numero) : !abertos.containsKey(numero)) {
                p.getInventory().setItem(i, null);
            }
        }
    }

    // --- Cliques ---

    @EventHandler
    public void clicou(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Tela)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player) || e.getRawSlot() < 0 || e.getRawSlot() >= e.getInventory().getSize()) return;
        Player p = (Player) e.getWhoClicked();
        long agora = System.currentTimeMillis();
        Long antes = ultimoClique.put(p.getUniqueId(), agora);
        if (antes != null && agora - antes < 250) return;
        Runnable acao = ((Tela) e.getInventory().getHolder()).acoes.get(e.getRawSlot());
        // Abrir outro inventário dentro do clique dá problema em algumas versões: no próximo tick
        if (acao != null) Bukkit.getScheduler().runTask(plugin, acao);
    }

    @EventHandler
    public void arrastou(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Tela) e.setCancelled(true);
    }

    // --- Ajudantes ---

    private Tela tela(int linhas, String titulo) {
        Tela t = new Tela();
        // O 1.8 não aceita título com mais de 32 letras
        String tit = "§8" + Loja.limpo(titulo, 30, "Loja");
        t.inv = Bukkit.createInventory(t, Math.max(1, Math.min(6, linhas)) * 9, tit.length() > 32 ? tit.substring(0, 32) : tit);
        return t;
    }

    private static void por(Tela t, int espaco, ItemStack item, Runnable acao) {
        t.inv.setItem(espaco, item);
        if (acao != null) t.acoes.put(espaco, acao);
    }

    private ItemStack botao(String item, String legado, String nome) {
        return item(Kit.icone(item, legado), 1, nome, Collections.<String>emptyList());
    }

    @SuppressWarnings("deprecation")
    private ItemStack item(Kit.Item it, int qtd, String nome, List<String> lore) {
        Kit.Peca peca = Kit.peca(plugin, it);
        Material m = peca == null ? null : moderno ? Material.matchMaterial(peca.id) : Bukkit.getUnsafe().getMaterialFromInternalName("minecraft:" + peca.id);
        int dano = peca == null ? 0 : peca.dano;
        if (m == null || m == Material.AIR) {
            m = Material.CHEST;
            dano = 0;
        }
        ItemStack item = new ItemStack(m, Math.max(1, Math.min(64, qtd)), (short) dano);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(nome);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static List<String> cinza(List<String> linhas) {
        List<String> s = new ArrayList<>();
        if (linhas.isEmpty()) return s;
        s.add("");
        for (String l : linhas) s.add("§7" + l);
        return s;
    }

    private static String preco(double v) {
        return v <= 0 ? "§aGrátis" : "§f" + Loja.reais(v);
    }

    private static String de(Loja.Variacao v) {
        return v.precoDe > v.preco && !v.gratis ? " §8§m" + Loja.reais(v.precoDe) : "";
    }

    private String gamertag(Player p) {
        Bedrock b = plugin.bedrock();
        return b == null ? null : b.gamertag(p.getUniqueId());
    }

    private static void avisar(Player p, String texto) {
        p.sendMessage("§2[Loja] §r" + texto);
    }

    private void som(Player p) {
        try {
            p.playSound(p.getLocation(), Kit.desde(plugin.versaoMc(), 1, 9, 0) ? "entity.player.levelup" : "random.levelup", 1f, 1.2f);
        } catch (RuntimeException semSom) {
            // só o som
        }
    }

    private void assincrono(Runnable r) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }

    private void principal(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }
}
