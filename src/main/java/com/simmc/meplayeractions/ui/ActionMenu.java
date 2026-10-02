package com.simmc.meplayeractions.ui;

import com.simmc.meplayeractions.action.ActionController;
import com.simmc.meplayeractions.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class ActionMenu implements Listener {
    private final JavaPlugin plugin;
    private final Supplier<ActionController> controllers;
    private final Supplier<Settings> settings;
    private final BiConsumer<Player, String[]> perform;

    private static final class Holder implements InventoryHolder {
        final Map<Integer, String[]> actions = new HashMap<>();
        final int page;
        final Inventory inventory;
        Holder(int page) { this.page = page; inventory = Bukkit.createInventory(this, 54, "玩家动作 · " + (page + 1)); }
        @Override public Inventory getInventory() { return inventory; }
    }
    public ActionMenu(JavaPlugin plugin, Supplier<ActionController> controllers,
                      Supplier<Settings> settings, BiConsumer<Player, String[]> perform) {
        this.plugin = plugin; this.controllers = controllers; this.settings = settings; this.perform = perform;
    }
    public void open(Player player, int requestedPage) {
        ActionController c = controllers.get(); Settings cfg = settings.get();
        List<String> clips = c.animations(player);
        Map<String, String> entries = new LinkedHashMap<>();
        for (Settings.CustomAction action : cfg.customActions.values())
            if (clips.contains(action.animation()) && (action.permission().isEmpty() || player.hasPermission(action.permission()))) entries.put(action.id(), action.label());
        if (cfg.rawPlay && player.hasPermission("mact.play")) for (String clip : clips) entries.putIfAbsent(clip, cfg.animationLabel(clip));
        List<Map.Entry<String, String>> display = List.copyOf(entries.entrySet());
        int pages = Math.max(1, (display.size() + 35) / 36), page = Math.max(0, Math.min(requestedPage, pages - 1));
        Holder holder = new Holder(page);
        for (int slot = 0; slot < 36 && page * 36 + slot < display.size(); slot++) {
            var entry = display.get(page * 36 + slot);
            button(holder, slot, Material.PAPER, entry.getValue(), List.of("§7动作名：" + entry.getKey(), "§e点击展示动画", "§7姿态动画不会改变真实姿态"), "play", entry.getKey());
        }
        if (player.hasPermission("mact.sit")) button(holder, 45, Material.OAK_STAIRS, "真实坐下", List.of("§7需要 GSit；潜行可起身"), "pose", "sit");
        if (player.hasPermission("mact.crawl")) button(holder, 46, Material.LEATHER_BOOTS, "真实爬行", List.of("§7需要 GSit 与爬行动画"), "pose", "crawl");
        if (cfg.allowFlight && player.hasPermission("mact.flight"))
            button(holder, 47, Material.FEATHER, "真实飞行开关", List.of("§7切换本插件授予的飞行"), "pose", "fly");
        button(holder, 48, Material.REDSTONE, "停止手动动作", List.of(), "stop");
        button(holder, 49, Material.BARRIER, "重置动作与姿态", List.of(), "reset");
        button(holder, 50, Material.LEVER, "坐姿动画同步开关", List.of("§7不会改变真实坐下状态"), "sync", "sit",
                c.syncEnabled(player, com.simmc.meplayeractions.action.SyncFeature.SIT) ? "off" : "on");
        if (page > 0) button(holder, 51, Material.ARROW, "上一页", List.of(), "page", Integer.toString(page - 1));
        if (page + 1 < pages) button(holder, 52, Material.ARROW, "下一页", List.of(), "page", Integer.toString(page + 1));
        button(holder, 53, Material.CHEST, "关闭", List.of(), "close");
        player.openInventory(holder.inventory);
    }
    private static void button(Holder holder, int slot, Material material, String title, List<String> lore, String... action) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b" + title); meta.setLore(lore); item.setItemMeta(meta);
        holder.inventory.setItem(slot, item); holder.actions.put(slot, action);
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String[] action = holder.actions.get(event.getRawSlot());
        if (action == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            if (action[0].equals("page")) open(player, Integer.parseInt(action[1]));
            else { player.closeInventory(); if (!action[0].equals("close")) perform.accept(player, action); }
        });
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder && event.getRawSlots().stream().anyMatch(i -> i < 54))
            event.setCancelled(true);
    }
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) player.closeInventory();
    }
}
