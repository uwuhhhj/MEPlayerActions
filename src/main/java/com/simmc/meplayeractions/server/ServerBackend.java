package com.simmc.meplayeractions.server;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.List;

/** Bootstrap boundary: implementations with optional APIs are loaded only after dependency checks. */
public interface ServerBackend extends AutoCloseable {
    default boolean modelEngine() { return false; }
    void start();
    String diagnosis();
    void status(CommandSender sender);
    void handleAction(Player player, String[] args);
    List<String> tabComplete(CommandSender sender, Command command, String alias, String[] args);
    void forget(Player player);
    @Override void close();
}
