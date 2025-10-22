package me.d15c07d.chunkcleaners.task;

import me.d15c07d.chunkcleaners.ChunkCleanersPlugin;
import me.d15c07d.chunkcleaners.ItemFactory;
import me.d15c07d.chunkcleaners.config.ConfigManager;
import me.d15c07d.chunkcleaners.integration.IntegrationManager;
import me.d15c07d.chunkcleaners.storage.PersistenceManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkCleanerManager {

    private final ChunkCleanersPlugin plugin;
    private final PersistenceManager persistence;
    private final ConfigManager config;
    private final IntegrationManager integration;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // key: unique id (UUID), value: active cleaner
    private final Map<UUID, ChunkCleanerTask> active = new ConcurrentHashMap<>();

    public ChunkCleanerManager(ChunkCleanersPlugin plugin, PersistenceManager persistence, ConfigManager config, IntegrationManager integration) {
        this.plugin = plugin;
        this.persistence = persistence;
        this.config = config;
        this.integration = integration;
    }

    public void giveCleanerItem(OfflinePlayer player, ConfigManager.CleanerType type, int amount) {
        ItemStack item = ItemFactory.createCleanerItem(plugin, type, amount);
        if (player.isOnline()) {
            ((Player) player).getInventory().addItem(item);
        } else {
            plugin.getLogger().info("Can't deliver cleaner item to offline player in this sample - drop into console or use other delivery.");
        }
    }

    public void startCleaner(Player owner, Location placeLocation, ConfigManager.CleanerType type) {
        Chunk chunk = placeLocation.getChunk();
        UUID id = UUID.randomUUID();
        // Pass placed block coordinates to task so hologram can anchor exactly above placed block
        int bx = placeLocation.getBlockX();
        int by = placeLocation.getBlockY();
        int bz = placeLocation.getBlockZ();
        ChunkCleanerTask task = new ChunkCleanerTask(id, owner.getUniqueId(), owner.getName(),
                chunk.getX(), chunk.getZ(), placeLocation.getWorld().getName(),
                type.getKey(), type.getSize(), type.getDurationSeconds(), plugin,
                bx, by, bz);
        active.put(id, task);
        task.start();
    }

    public boolean isChunkHasCleaner(Chunk chunk) {
        return active.values().stream().anyMatch(c -> c.getWorldName().equals(chunk.getWorld().getName()) && c.getChunkX() == chunk.getX() && c.getChunkZ() == chunk.getZ());
    }

    public boolean cancelNearbyCleaner(Player player) {
        Optional<ChunkCleanerTask> found = active.values().stream()
                .filter(t -> t.getOwnerUuid().equals(player.getUniqueId()))
                .filter(t -> {
                    // within same chunk
                    Chunk c = player.getLocation().getChunk();
                    return c.getX() == t.getChunkX() && c.getZ() == t.getChunkZ() && c.getWorld().getName().equals(t.getWorldName());
                }).findFirst();
        if (found.isPresent()) {
            found.get().cancel();
            active.remove(found.get().getId());
            return true;
        }
        return false;
    }

    public boolean cancelByOwnerName(String playerName) {
        Optional<ChunkCleanerTask> found = active.values().stream()
                .filter(t -> t.getOwnerName().equalsIgnoreCase(playerName))
                .findFirst();
        if (found.isPresent()) {
            found.get().cancel();
            active.remove(found.get().getId());
            return true;
        }
        return false;
    }

    public List<String> listActive(int page, int pageSize) {
        List<ChunkCleanerTask> list = new ArrayList<>(active.values());
        list.sort(Comparator.comparing(ChunkCleanerTask::getStartedAt));
        int maxPages = Math.max(1, (int) Math.ceil((double) list.size() / pageSize));
        page = Math.min(page, maxPages);
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, list.size());
        List<String> out = new ArrayList<>();
        out.add(config.getMessage("list_header").replace("{page}", String.valueOf(page)).replace("{max}", String.valueOf(maxPages)));
        for (int i = start; i < end; i++) {
            ChunkCleanerTask t = list.get(i);
            long age = (Instant.now().getEpochSecond() - t.getStartedAt());
            out.add(config.getMessage("list_entry")
                    .replace("{index}", String.valueOf(i + 1))
                    .replace("{owner}", t.getOwnerName())
                    .replace("{coords}", t.getWorldName() + ":" + t.getChunkX() + "," + t.getChunkZ())
                    .replace("{type}", t.getTypeKey())
                    .replace("{age}", String.valueOf(age)));
        }
        return out;
    }

    public void removeTask(UUID id) {
        active.remove(id);
    }

    public Collection<ChunkCleanerTask> getActiveTasks() {
        return active.values();
    }

    public void shutdown() {
        // cancel running tasks
        for (ChunkCleanerTask t : active.values()) {
            t.cancel();
        }
        // optionally persistence save is handled elsewhere
        active.clear();
    }

    public void onConfigReload() {
        // apply new config to running tasks: update durations/holograms text etc.
        active.values().forEach(ChunkCleanerTask::onConfigReload);
    }

    public PersistenceManager getPersistence() {
        return persistence;
    }

    public void addLoadedTask(ChunkCleanerTask t) {
        active.put(t.getId(), t);
        t.resume();
    }
}