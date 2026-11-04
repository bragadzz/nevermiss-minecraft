package com.nevermissapps.minecraft;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/** Spigot, Paper e forks, do 1.7.10 ao mais novo, e o Folia, num jar só. */
public final class NeverMiss extends JavaPlugin implements Listener, Jogo {
    private final Set<String> online = ConcurrentHashMap.newKeySet();
    private Agenda agenda;
    private Bedrock bedrock;
    private Laco laco;
    // null no Folia: o menu teria que abrir na região do jogador
    private LojaMenu loja;
    // null no Folia: lá o kit ainda não sai (o comando do jogador tem que rodar na região dele)
    private int[] versao;
    private Method chaveMinecraft;
    private Method encantamentoPorChave;

    @Override
    public void onEnable() {
        agenda = new Agenda(this);
        bedrock = Bedrock.detectar(getClass().getClassLoader());
        versao = agenda.folia ? null : Kit.versao(Bukkit.getBukkitVersion());
        try {
            // Do 1.13 em diante: Enchantment.getByKey(NamespacedKey.minecraft(id)), que o jar do 1.8.8 não tem
            Class<?> chave = Class.forName("org.bukkit.NamespacedKey");
            chaveMinecraft = chave.getMethod("minecraft", String.class);
            encantamentoPorChave = Enchantment.class.getMethod("getByKey", chave);
        } catch (ReflectiveOperationException antigo) {
            // antes do 1.13 o core não pergunta
        }
        try {
            laco = new Laco(this, getDataFolder(), getDescription().getVersion());
        } catch (IOException e) {
            getLogger().severe("Não consegui abrir " + getDataFolder() + " (" + e.getMessage() + "). Nada vai ser entregue.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (Player p : jogadores()) online.add(p.getName().toLowerCase(Locale.ROOT));
        getServer().getPluginManager().registerEvents(this, this);
        if (!agenda.folia) {
            loja = new LojaMenu(this, laco.loja);
            getServer().getPluginManager().registerEvents(loja, this);
        }
        laco.configurar();
    }

    @Override
    public void onDisable() {
        if (laco != null) laco.desligar();
    }

    /** No 1.7.10 o getOnlinePlayers devolve Player[]; do 1.8 em diante, uma Collection. */
    @SuppressWarnings("unchecked")
    static Collection<? extends Player> jogadores() {
        try {
            Object r = Bukkit.class.getMethod("getOnlinePlayers").invoke(null);
            return r instanceof Player[] ? Arrays.asList((Player[]) r) : (Collection<? extends Player>) r;
        } catch (ReflectiveOperationException e) {
            return Collections.emptyList();
        }
    }

    @EventHandler
    public void entrou(PlayerJoinEvent e) {
        String nick = e.getPlayer().getName();
        online.add(nick.toLowerCase(Locale.ROOT));
        laco.entrou(nick);
    }

    @EventHandler
    public void saiu(PlayerQuitEvent e) {
        online.remove(e.getPlayer().getName().toLowerCase(Locale.ROOT));
        laco.saiu(e.getPlayer().getName());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("nevermiss") && args.length > 0 && args[0].equalsIgnoreCase("comprar")) {
            // O Pix de um produto direto, para o "Pagar" do menu de outro plugin (pelo console). O nevermiss.admin vem do plugin.yml
            Player p = args.length == 3 ? Bukkit.getPlayerExact(args[1]) : null;
            if (args.length != 3) sender.sendMessage("[Loja] Use: nevermiss comprar <jogador> <id do produto>");
            else if (loja == null) sender.sendMessage("[Loja] A /loja ainda não funciona no Folia.");
            else if (p == null) sender.sendMessage("[Loja] " + args[1] + " não está online.");
            else loja.comprar(p, args[2], texto -> agenda.responder(sender, texto));
        } else if (command.getName().equalsIgnoreCase("nevermiss") && args.length == 2 && args[0].equalsIgnoreCase("loja")) {
            // Para o NPC ou o menu de outro plugin abrir a /loja de um jogador (pelo console)
            Player p = Bukkit.getPlayerExact(args[1]);
            if (loja == null) sender.sendMessage("[NeverMiss] A /loja ainda não funciona no Folia.");
            else if (p == null) sender.sendMessage("[NeverMiss] " + args[1] + " não está online.");
            else loja.abrir(p);
        } else if (command.getName().equalsIgnoreCase("nevermiss")) {
            laco.comando(args, sender instanceof ConsoleCommandSender, texto -> agenda.responder(sender, texto));
        } else if (command.getName().equalsIgnoreCase("nmloja")) {
            if (!(sender instanceof Player)) sender.sendMessage("[NeverMiss] A /loja é do jogador. Para abrir a de alguém: nevermiss loja <jogador>");
            else if (loja == null) sender.sendMessage("§cA /loja ainda não funciona neste servidor (Folia).");
            // /loja <categoria>: o [Ver na loja] do aviso de compra abre direto no produto
            else loja.abrir((Player) sender, args.length > 0 ? args[0] : null);
        } else if (sender instanceof Player) {
            laco.kit(sender.getName(), args, texto -> agenda.responder(sender, texto));
        } else {
            sender.sendMessage("[NeverMiss] O /" + label + " é do jogador.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // nevermiss comprar <jogador> <id>: o jogador pelo tab de sempre (null) e os ids do catálogo já lido, sem consultar a API
        if (!command.getName().equalsIgnoreCase("nevermiss") || args.length != 3 || !args[0].equalsIgnoreCase("comprar")) return null;
        List<String> ids = new ArrayList<>();
        if (sender.hasPermission("nevermiss.admin")) for (String id : laco.loja.ids()) if (id.startsWith(args[2])) ids.add(id);
        return ids;
    }

    // --- Jogo ---

    @Override
    public boolean rodar(String comando) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), comando);
    }

    @Override
    public boolean online(String nick) {
        return online.contains(nick.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("deprecation")
    @Override
    public UUID uuid(String nick) {
        return Bukkit.getOfflinePlayer(nick).getUniqueId();
    }

    @Override
    public boolean onlineMode() {
        return Bukkit.getOnlineMode();
    }

    @Override
    public String versao() {
        return Bukkit.getVersion();
    }

    @Override
    public void log(String texto) {
        getLogger().info(texto);
    }

    @Override
    public void aviso(String texto) {
        getLogger().warning(texto);
    }

    @Override
    public <T> T naPrincipal(Callable<T> tarefa) throws Exception {
        return agenda.naPrincipal(tarefa);
    }

    @Override
    public Runnable depois(long segundos, Runnable tarefa) {
        return agenda.depois(segundos, tarefa);
    }

    @Override
    public String plataforma() {
        return agenda.folia ? "folia" : "bukkit";
    }

    @Override
    public Bedrock bedrock() {
        return bedrock;
    }

    // --- Kit ---

    @Override
    public int[] versaoMc() {
        return versao;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean existe(String item) {
        if (Kit.desde(versao, 1, 13, 0)) return Material.matchMaterial(item) != null;
        // Antes do 1.13 o id vanilla (o do comando) só se acha pelo registro do jogo
        Material m = Bukkit.getUnsafe().getMaterialFromInternalName("minecraft:" + item);
        return m != null && m != Material.AIR;
    }

    @Override
    public boolean existeEncantamento(String id) {
        if (encantamentoPorChave == null) return true;
        try {
            return encantamentoPorChave.invoke(null, chaveMinecraft.invoke(null, id)) != null;
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    @Override
    public boolean vazio(String nome, int espaco) {
        Player p = Bukkit.getPlayerExact(nome);
        ItemStack i = p == null ? null : p.getInventory().getItem(espaco);
        return p != null && (i == null || i.getType() == Material.AIR);
    }

    @Override
    public boolean darPeca(String nome, Kit.Peca peca, int espaco) {
        // "minecraft:" na frente: o /give de outro plugin (o do EssentialsX) não pega o comando.
        if (Kit.desde(versao, 1, 9, 0)) return rodar("minecraft:" + Kit.comando(versao, nome, peca, espaco));
        return darPelaApi(nome, peca, espaco);
    }

    /** 1.7.10 e 1.8: o item montado pela API do Bukkit (o 1.7.10 não tem o replaceitem). */
    @SuppressWarnings("deprecation")
    private boolean darPelaApi(String nome, Kit.Peca peca, int espaco) {
        Player p = Bukkit.getPlayerExact(nome);
        Material m = Bukkit.getUnsafe().getMaterialFromInternalName("minecraft:" + peca.id);
        if (p == null || m == null || m == Material.AIR) return false;
        ItemStack item = new ItemStack(m, peca.qtd, (short) peca.dano);
        for (Map.Entry<String, Integer> e : peca.encantamentos.entrySet()) {
            Enchantment en = Enchantment.getById(Integer.parseInt(e.getKey()));
            if (en != null) item.addUnsafeEnchantment(en, e.getValue());
        }
        if (peca.nome != null || !peca.descricao.isEmpty()) {
            ItemMeta meta = item.getItemMeta();
            if (peca.nome != null) meta.setDisplayName(peca.nome);
            if (!peca.descricao.isEmpty()) meta.setLore(peca.descricao);
            item.setItemMeta(meta);
        }
        if (espaco >= 0) p.getInventory().setItem(espaco, item);
        else for (ItemStack sobra : p.getInventory().addItem(item).values()) p.getWorld().dropItem(p.getLocation(), sobra);
        return true;
    }

    @Override
    public void mensagem(String nome, String texto) {
        Player p = Bukkit.getPlayerExact(nome);
        if (p != null) p.sendMessage(texto);
    }
}
