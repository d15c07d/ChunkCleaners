package me.d15c07d.chunkcleaners.config;

import me.d15c07d.chunkcleaners.ChunkCleanersPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConfigManager {

    private final ChunkCleanersPlugin plugin;
    private FileConfiguration cfg;
    private final Map<String, CleanerType> types = new HashMap<>();
    private final AtomicBoolean hotReloadRunning = new AtomicBoolean(false);
    private Thread watchThread;

    public ConfigManager(ChunkCleanersPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        this.cfg = plugin.getConfig();
        loadTypes();
    }

    public void reload() {
        plugin.reloadConfig();
        this.cfg = plugin.getConfig();
        loadTypes();
    }

    private void loadTypes() {
        types.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("types");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection t = sec.getConfigurationSection(key);
            if (t == null) continue;
            String display = t.getString("display-name", key);
            String description = t.getString("description", "");
            int size = Math.max(1, t.getInt("size", 1));
            String block = t.getString("block", "END_PORTAL_FRAME");
            int duration = Math.max(1, t.getInt("duration", 10));
            CleanerType ct = new CleanerType(key, display, description, size, block, duration);
            types.put(key.toLowerCase(Locale.ROOT), ct);
        }
    }

    public Optional<CleanerType> getType(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(types.get(key.toLowerCase(Locale.ROOT)));
    }

    public Collection<CleanerType> getTypes() {
        return types.values();
    }

    public String getMessage(String path) {
        String raw = cfg.getString("messages." + path);
        return raw == null ? "" : raw;
    }

    public boolean isPreventSameChunk() {
        return cfg.getBoolean("prevent_same_chunk", true);
    }

    public boolean isSaveOnShutdown() {
        return cfg.getBoolean("save_on_shutdown", true);
    }

    public int getPollIntervalMs() {
        return cfg.getInt("hot_reload.poll_interval_ms", 1000);
    }

    // start a WatchService thread that watches the plugin folder for config changes and reloads instantly
    public void startHotReload() {
        if (!cfg.getBoolean("hot_reload.enabled", true)) return;
        if (hotReloadRunning.get()) return;
        hotReloadRunning.set(true);
        Path pluginDir = plugin.getDataFolder().toPath();
        String fileToWatch = cfg.getString("hot_reload.file", "config.yml");
        Path pathToWatch = pluginDir.resolve(fileToWatch);

        watchThread = new Thread(() -> {
            try {
                WatchService service = FileSystems.getDefault().newWatchService();
                pluginDir.register(service, StandardWatchEventKinds.ENTRY_MODIFY);
                while (hotReloadRunning.get()) {
                    WatchKey key = service.poll(getPollIntervalMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (key == null) continue;
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        Path changed = (Path) ev.context();
                        if (changed.endsWith(fileToWatch)) {
                            plugin.getLogger().info("Detected config.yml change - reloading ChunkCleaners...");
                            try {
                                reload();
                                plugin.getCleanerManager().onConfigReload();
                                plugin.getLogger().info("ChunkCleaners config reloaded.");
                            } catch (Exception ex) {
                                plugin.getLogger().warning("Failed to reload config: " + ex.getMessage());
                            }
                        }
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                plugin.getLogger().warning("Hot reload thread stopped: " + e.getMessage());
            }
        }, "ChunkCleaners-HotReload");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    public void stopHotReload() {
        hotReloadRunning.set(false);
        if (watchThread != null) watchThread.interrupt();
    }

    public static class CleanerType {
        private final String key;
        private final String displayName;
        private final String description;
        private final int size;
        private final String blockMaterial;
        private final int durationSeconds;

        public CleanerType(String key, String displayName, String description, int size, String blockMaterial, int durationSeconds) {
            this.key = key;
            this.displayName = displayName;
            this.description = description;
            this.size = size;
            this.blockMaterial = blockMaterial;
            this.durationSeconds = durationSeconds;
        }

        public String getKey() {
            return key;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public int getSize() {
            return size;
        }

        public String getBlockMaterial() {
            return blockMaterial;
        }

        public int getDurationSeconds() {
            return durationSeconds;
        }
    }
}