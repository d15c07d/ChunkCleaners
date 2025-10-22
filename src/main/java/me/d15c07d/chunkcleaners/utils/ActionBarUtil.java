package me.d15c07d.chunkcleaners.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public class ActionBarUtil {

    public static void sendActionBar(Player player, Component component) {
        // Paper provides Player#sendActionBar(Component) in some versions; fallback to sendMessage as last resort
        try {
            player.sendActionBar(component);
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            player.sendMessage(component);
        }
    }
}