package me.d15c07d.chunkcleaners.listeners;

import me.d15c07d.chunkcleaners.ChunkCleanersPlugin;
import me.d15c07d.chunkcleaners.config.ConfigManager;
import me.d15c07d.chunkcleaners.task.ChunkCleanerManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.block.Block;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class PlacementListener implements Listener {

    private final ChunkCleanersPlugin plugin;
    private final ChunkCleanerManager manager;
    private final NamespacedKey typeKey;

    public PlacementListener(ChunkCleanersPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.getCleanerManager();
        this.typeKey = new NamespacedKey(plugin, "chunkcleaner-type");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent ev) {
        ItemStack item = ev.getItemInHand();
        if (item == null) return;
        if (!(ev.getPlayer() instanceof Player)) return;
        Player p = ev.getPlayer();

        // 1) Try PDC detection first
        String configuredTypeKey = null;
        try {
            if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(typeKey, PersistentDataType.STRING)) {
                configuredTypeKey = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
            }
        } catch (Throwable ignored) {}

        if (configuredTypeKey == null) {
            Block placed = ev.getBlockPlaced();
            if (placed != null) {
                Material mat = placed.getType();
                // iterate configured types to find matches
                var typesSection = plugin.getConfig().getConfigurationSection("types");
                if (typesSection != null) {
                    List<String> matches = new ArrayList<>();
                    for (String key : typesSection.getKeys(false)) {
                        String cfgBlock = plugin.getConfig().getString("types." + key + ".block", "");
                        if (cfgBlock != null && !cfgBlock.isEmpty()) {
                            try {
                                Material cfgMat = Material.valueOf(cfgBlock.toUpperCase(Locale.ROOT));
                                if (cfgMat == mat) matches.add(key);
                            } catch (IllegalArgumentException ignored) { /* invalid material in config - skip */ }
                        }
                    }
                    if (matches.size() == 1) {
                        configuredTypeKey = matches.get(0);
                    } else if (matches.size() > 1) {
                        // ambiguous: choose first as fallback
                        configuredTypeKey = matches.get(0);
                    }
                }
            }
        }

        if (configuredTypeKey == null) return;

        // Validate type exists in config
        var typeOpt = plugin.getConfigManager().getType(configuredTypeKey);
        if (typeOpt.isEmpty()) return; // not a configured cleaner type; skip

        ConfigManager.CleanerType type = typeOpt.get();

        // Protection check: canPlaceInRegion (IntegrationManager) - if false, cancel placement
        Location loc = ev.getBlockPlaced().getLocation();
        if (!plugin.getIntegrationManager().canPlaceInRegion(p, loc)) {
            p.sendMessage(plugin.getConfig().getString("messages.cannot_place_in_protected", "&cCannot place chunk cleaners in protected regions."));
            ev.setCancelled(true);
            return;
        }

        // Prevent placing in same chunk if configured and a cleaner exists
        boolean preventSame = plugin.getConfig().getBoolean("prevent_same_chunk", true);
        if (preventSame) {
            if (manager.isChunkHasCleaner(ev.getBlockPlaced().getChunk())) {
                p.sendMessage(plugin.getConfig().getString("messages.cannot_place_in_same_chunk", "&cA chunk cleaner is already active in this chunk."));
                ev.setCancelled(true);
                return;
            }
        }

        // Everything ok -> start cleaner (manager handles putting task in map)
        // Note: startCleaner expects Player and Location and CleanerType
        try {
            manager.startCleaner(p, loc, type);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to start cleaner on placement: " + t.getMessage());
            p.sendMessage(plugin.getConfig().getString("messages.invalid_type", "&cFailed to start cleaner."));
            // Do not cancel placement by default; let server place the block as user intended.
        }
    }
}