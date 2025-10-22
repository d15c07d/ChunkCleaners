package me.d15c07d.chunkcleaners.storage;

import me.d15c07d.chunkcleaners.ChunkCleanersPlugin;
import me.d15c07d.chunkcleaners.task.ChunkCleanerTask;
import me.d15c07d.chunkcleaners.task.ChunkCleanerManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class PersistenceManager {

    private final ChunkCleanersPlugin plugin;
    private final File file;
    private final YamlConfiguration yaml;
    private ChunkCleanerManager manager;

    private final AtomicBoolean autosaveRunning = new AtomicBoolean(false);
    private BukkitRunnable autosaveTask;

    public PersistenceManager(ChunkCleanersPlugin plugin) {
        this.plugin = plugin;
        String path = plugin.getConfig().getString("persistence-file", "data/active-cleaners.yml");
        this.file = new File(plugin.getDataFolder(), path);
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        this.yaml = YamlConfiguration.loadConfiguration(file);

        try {
            boolean enabled = plugin.getConfig().getBoolean("persistence.autosave_enabled", true);
            int interval = plugin.getConfig().getInt("persistence.autosave_interval_seconds", 60);
            if (enabled && interval > 0) startAutosave(interval);
        } catch (Throwable t) {
            plugin.getLogger().fine("PersistenceManager autosave not started: " + t.getMessage());
        }
    }

    public void setCleanerManager(ChunkCleanerManager manager) {
        this.manager = manager;
    }

    public synchronized void save() {
        yaml.set("active", null);
        if (manager == null) return;
        for (ChunkCleanerTask t : manager.getActiveTasks()) {
            writeTaskBase(t);
        }
        try {
            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save active cleaners: " + e.getMessage());
        }
    }

    public synchronized void saveTaskProgress(ChunkCleanerTask t) {
        if (t == null) return;
        writeTaskBase(t);

        String key = "active." + t.getId().toString() + ".progress";
        yaml.set(key + ".currentChunkIndex", t.getCurrentChunkIndex());
        yaml.set(key + ".currentY", t.getCurrentY());
        yaml.set(key + ".startedAt", t.getStartedAt());

        try {
            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save task progress for " + t.getId() + ": " + e.getMessage());
        }
    }

    private void writeTaskBase(ChunkCleanerTask t) {
        String base = "active." + t.getId().toString();
        yaml.set(base + ".ownerUuid", t.getOwnerUuid().toString());
        yaml.set(base + ".ownerName", t.getOwnerName());
        yaml.set(base + ".chunkX", t.getChunkX());
        yaml.set(base + ".chunkZ", t.getChunkZ());
        yaml.set(base + ".world", t.getWorldName());
        yaml.set(base + ".type", t.getTypeKey());
        yaml.set(base + ".size", t.getSize());
        yaml.set(base + ".duration", t.getDurationSeconds());
        yaml.set(base + ".startedAt", t.getStartedAt());

        // Persist placement block coords
        yaml.set(base + ".placedBlockX", t.getPlacedBlockX());
        yaml.set(base + ".placedBlockY", t.getPlacedBlockY());
        yaml.set(base + ".placedBlockZ", t.getPlacedBlockZ());
    }

    public void load() {
        ConfigurationSection sec = yaml.getConfigurationSection("active");
        if (sec == null) return;
        if (manager == null) {
            plugin.getLogger().warning("PersistenceManager.load() called before manager set.");
            return;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection t = sec.getConfigurationSection(key);
            try {
                UUID id = UUID.fromString(key);
                UUID owner = UUID.fromString(t.getString("ownerUuid"));
                String ownerName = t.getString("ownerName", "unknown");
                int chunkX = t.getInt("chunkX");
                int chunkZ = t.getInt("chunkZ");
                String world = t.getString("world");
                String type = t.getString("type");
                int size = t.getInt("size");
                int duration = t.getInt("duration");
                long startedAt = t.getLong("startedAt", System.currentTimeMillis() / 1000L);

                int currentChunkIndex = 0;
                int currentY = 0;
                ConfigurationSection progress = t.getConfigurationSection("progress");
                if (progress != null) {
                    currentChunkIndex = progress.getInt("currentChunkIndex", 0);
                    currentY = progress.getInt("currentY", 0);
                }

                // Read placed block coords (fallback to chunk center if missing)
                int placedX = t.getInt("placedBlockX", (chunkX << 4) + 8);
                int placedY = t.getInt("placedBlockY", 64);
                int placedZ = t.getInt("placedBlockZ", (chunkZ << 4) + 8);

                // create task and restore progress
                ChunkCleanerTask task = new ChunkCleanerTask(id, owner, ownerName, chunkX, chunkZ, world, type, size, duration, plugin, placedX, placedY, placedZ);
                task.setStartedAt(startedAt);
                task.setCurrentChunkIndex(currentChunkIndex);
                if (currentY != 0) task.setCurrentY(currentY);
                manager.addLoadedTask(task);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load cleaner " + key + ": " + e.getMessage());
            }
        }
    }

    public synchronized void startAutosave(int intervalSeconds) {
        if (autosaveRunning.get()) return;
        autosaveRunning.set(true);
        autosaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    save();
                } catch (Throwable t) {
                    plugin.getLogger().fine("Autosave failed: " + t.getMessage());
                }
            }
        };
        autosaveTask.runTaskTimer(plugin, intervalSeconds * 20L, intervalSeconds * 20L);
    }

    public synchronized void stopAutosave() {
        autosaveRunning.set(false);
        if (autosaveTask != null) {
            try { autosaveTask.cancel(); } catch (Throwable ignored) {}
            autosaveTask = null;
        }
    }
}