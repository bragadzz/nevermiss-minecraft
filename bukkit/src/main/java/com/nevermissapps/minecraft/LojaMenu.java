package com.nevermissapps.minecraft;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
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
    private static final int POR_PAGINA = 45;

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

        Aberto(String numero, UUID jogador, long vence) {
            this.numero = numero;
            this.jogador = jogador;
            this.vence = vence;
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
                else if (c.produtos.isEmpty()) avisar(p, "§7Nenhum produto à venda neste servidor agora.");
                else produtos(p, c, 0);
            });
        });
    }

    private void produtos(Player p, Loja.Catalogo c, int pagina) {
        int total = c.produtos.size();
        int paginas = (total + POR_PAGINA - 1) / POR_PAGINA;
        int inicio = pagina * POR_PAGINA;
        int aqui = Math.min(POR_PAGINA, total - inicio);
        int linhas = (aqui + 8) / 9 + (paginas > 1 ? 1 : 0);
        Tela t = tela(linhas, c.nome == null ? "Loja" : "Loja · " + c.nome);
        for (int i = 0; i < aqui; i++) {
            Loja.Produto pr = c.produtos.get(inicio + i);
            List<String> lore = new ArrayList<>();
            if (pr.esgotado()) lore.add("§cEsgotado");
            else lore.add((pr.aPartirDe() ? "§7a partir de " : "") + preco(pr.menor()));
            lore.addAll(cinza(pr.descricao));
            lore.add("");
            lore.add(pr.esgotado() ? "§8Volte mais tarde" : "§eClique para comprar");
            por(t, i, item(pr.icone, 1, (pr.esgotado() ? "§c" : "§a") + pr.nome, lore), () -> {
                if (pr.variacoes.size() == 1) confirmar(p, c, pr, pr.variacoes.get(0), pr.variacoes.get(0).min, pagina);
                else variacoes(p, c, pr, pagina);
            });
        }
        if (paginas > 1) {
            int base = (linhas - 1) * 9;
            if (pagina > 0) por(t, base + 3, botao("arrow", "ARROW", "§fPágina anterior"), () -> produtos(p, c, pagina - 1));
            por(t, base + 4, botao("paper", "PAPER", "§7Página " + (pagina + 1) + " de " + paginas), null);
            if (pagina < paginas - 1) por(t, base + 5, botao("arrow", "ARROW", "§fPróxima página"), () -> produtos(p, c, pagina + 1));
        }
        p.openInventory(t.inv);
    }

    private void variacoes(Player p, Loja.Catalogo c, Loja.Produto pr, int pagina) {
        int n = pr.variacoes.size();
        Tela t = tela((n + 8) / 9 + 1, pr.nome);
        boolean bedrock = gamertag(p) != null;
        for (int i = 0; i < n; i++) {
            Loja.Variacao v = pr.variacoes.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(v.esgotado ? "§cEsgotado" : preco(v.gratis ? 0 : v.preco) + de(v));
            if (v.restam >= 0 && !v.esgotado) lore.add("§7Restam " + v.restam);
            lore.addAll(cinza(v.descricao));
            lore.add("");
            lore.add(v.esgotado ? "§8Volte mais tarde" : bedrock && !v.bedrock ? "§cSó para o Minecraft Java" : "§eClique para comprar");
            por(t, i, item(v.icone, 1, (v.esgotado ? "§c" : "§a") + v.nome, lore), () -> confirmar(p, c, pr, v, v.min, pagina));
        }
        por(t, ((n + 8) / 9) * 9, botao("arrow", "ARROW", "§fVoltar"), () -> produtos(p, c, pagina));
        p.openInventory(t.inv);
    }

    /** A quantidade e o botão de pagar. Mudar a quantidade monta a tela de novo. */
    private void confirmar(Player p, Loja.Catalogo c, Loja.Produto pr, Loja.Variacao v, int qtd, int pagina) {
        if (v.esgotado) {
            avisar(p, "§c" + v.nome + " está esgotado.");
            return;
        }
        boolean bedrock = gamertag(p) != null;
        Tela t = tela(3, v.nome.equals(pr.nome) ? pr.nome : pr.nome + " · " + v.nome);
        double total = v.total(qtd);
        List<String> lore = new ArrayList<>();
        lore.add("§7Preço: " + preco(v.gratis ? 0 : v.preco) + de(v));
        if (v.max > v.min) lore.add("§7Quantidade: §f" + qtd);
        if (v.descontoPct > 0 && v.max >= v.descontoAPartirDe && !v.gratis) {
            lore.add((qtd >= v.descontoAPartirDe ? "§a" : "§7") + Loja.limpo(String.format("%.0f", v.descontoPct), 4, "") + "% de desconto a partir de " + v.descontoAPartirDe);
        }
        lore.add("§7Total: " + preco(total));
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
                por(t, s[0], b, () -> confirmar(p, c, pr, v, nova, pagina));
            }
        }
        por(t, 18, botao("arrow", "ARROW", "§fVoltar"), () -> {
            if (pr.variacoes.size() == 1) produtos(p, c, pagina);
            else variacoes(p, c, pr, pagina);
        });
        if (bedrock && !v.bedrock) {
            por(t, 22, item(Kit.icone("barrier", "BARRIER"), 1, "§cSó para o Minecraft Java",
                    Collections.singletonList("§7Este produto não entrega para jogador Bedrock.")), null);
        } else {
            String rotulo = v.gratis ? "§aResgatar grátis" : "§aPagar " + preco(total) + " com Pix";
            List<String> como = v.gratis ? Collections.singletonList("§71 por jogador")
                    : Arrays.asList("§7O QR Code aparece num mapa", "§7na sua mão. Vence em 15 min.");
            por(t, 22, item(Kit.icone("emerald", "EMERALD"), 1, rotulo, como), () -> {
                p.closeInventory();
                pagar(p, pr, v, qtd);
            });
        }
        por(t, 26, botao("barrier", "BARRIER", "§cCancelar"), p::closeInventory);
        p.openInventory(t.inv);
    }

    private void pagar(Player p, Loja.Produto pr, Loja.Variacao v, int qtd) {
        if (!v.gratis && p.getInventory().firstEmpty() < 0) {
            avisar(p, "§cLibere um espaço no inventário para receber o mapa do Pix.");
            return;
        }
        if (!gerando.add(p.getUniqueId())) return;
        String gamertag = gamertag(p);
        String nick = gamertag != null ? gamertag : p.getName();
        String chave = UUID.randomUUID().toString();
        avisar(p, v.gratis ? "§7Resgatando..." : "§7Gerando o Pix...");
        assincrono(() -> {
            Loja.Pedido ped;
            try {
                ped = loja.comprar(pr, v, qtd, nick, gamertag != null, chave);
            } catch (Loja.Erro e) {
                gerando.remove(p.getUniqueId());
                principal(() -> avisar(p, "§c" + e.getMessage()));
                return;
            }
            principal(() -> {
                gerando.remove(p.getUniqueId());
                if ("pago".equals(ped.status)) {
                    avisar(p, "§aResgatado! " + v.nome + " chega em instantes (pedido #" + ped.numero + ").");
                    som(p);
                } else if ("aguardando".equals(ped.status)) {
                    entregarMapa(p, v, ped);
                } else {
                    avisar(p, "§cEste pedido não está mais aberto (pedido #" + ped.numero + ").");
                }
            });
        });
    }

    private void entregarMapa(Player p, Loja.Variacao v, Loja.Pedido ped) {
        ItemStack mapa;
        try {
            mapa = mapa(p, ped);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.aviso("A /loja não conseguiu montar o mapa do Pix: " + e);
            avisar(p, "§cNão deu para mostrar o QR Code aqui. Pedido #" + ped.numero + ": use o copia e cola abaixo.");
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
        abertos.put(ped.numero, new Aberto(ped.numero, p.getUniqueId(), vence));
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
    private ItemStack mapa(Player p, Loja.Pedido ped) throws ReflectiveOperationException {
        MapView view = Bukkit.createMap(p.getWorld());
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
        view.setScale(MapView.Scale.CLOSEST);
        view.addRenderer(new Qr(ped));
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

    /** O QR pintado uma vez (o canvas fica): branco, os módulos pretos de 2 pixels e a margem em volta. */
    private static final class Qr extends MapRenderer {
        private final Loja.Pedido ped;
        private boolean feito;

        Qr(Loja.Pedido ped) {
            super(false);
            this.ped = ped;
        }

        @SuppressWarnings("deprecation")
        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (feito) return;
            feito = true;
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
            if ("pago".equals(ped.status)) principal(() -> fechar(a, "§aPagamento aprovado! Pedido #" + a.numero + ": a entrega chega em instantes."));
            else if ("vencido".equals(ped.status)) principal(() -> fechar(a, "§cO Pix do pedido #" + a.numero + " venceu. Abra a /loja para gerar outro."));
            else if (!"aguardando".equals(ped.status)) principal(() -> fechar(a, "§cO pedido #" + a.numero + " foi cancelado."));
        }
        conferirDepois();
    }

    private void fechar(Aberto a, String msg) {
        if (abertos.remove(a.numero) == null) return;
        // Offline: o mapa sai quando ele entrar (o pedido já não está em abertos)
        Player p = Bukkit.getPlayer(a.jogador);
        if (p == null) return;
        tirarMapas(p, Collections.singleton(a.numero));
        avisar(p, msg);
        if (msg.startsWith("§a")) som(p);
    }

    /** Entrou: os mapas de pedido que acabaram (ou que ficaram de antes de o servidor reiniciar) saem. */
    @EventHandler
    public void entrou(PlayerJoinEvent e) {
        tirarMapas(e.getPlayer(), null);
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
