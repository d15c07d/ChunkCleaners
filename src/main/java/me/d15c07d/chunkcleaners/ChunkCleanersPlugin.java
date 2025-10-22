package me.d15c07d.chunkcleaners;

import me.d15c07d.chunkcleaners.commands.ChunkCleanersCommand;
import me.d15c07d.chunkcleaners.config.ConfigManager;
import me.d15c07d.chunkcleaners.integration.IntegrationManager;
import me.d15c07d.chunkcleaners.listeners.PlacementListener;
import me.d15c07d.chunkcleaners.storage.PersistenceManager;
import me.d15c07d.chunkcleaners.task.ChunkCleanerManager;
import org.bukkit.plugin.java.JavaPlugin;

public class ChunkCleanersPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private PersistenceManager persistenceManager;
    private IntegrationManager integrationManager;
    private ChunkCleanerManager cleanerManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.persistenceManager = new PersistenceManager(this);

        this.integrationManager = new IntegrationManager(this);
        this.integrationManager.initialize();

        this.cleanerManager = new ChunkCleanerManager(this, persistenceManager, configManager, integrationManager);

        this.persistenceManager.setCleanerManager(cleanerManager);
        this.persistenceManager.load();

        new ChunkCleanersCommand(this);

        getServer().getPluginManager().registerEvents(new PlacementListener(this), this);

        getLogger().info("ChunkCleaners enabled.");
    }

    @Override
    public void onDisable() {
        if (cleanerManager != null) cleanerManager.shutdown();
        if (integrationManager != null) integrationManager.shutdown();
        if (persistenceManager != null) persistenceManager.save();
        getLogger().info("ChunkCleaners disabled.");
    }

    public ConfigManager getConfigManager() { return configManager; }
    public PersistenceManager getPersistenceManager() { return persistenceManager; }
    public IntegrationManager getIntegrationManager() { return integrationManager; }
    public ChunkCleanerManager getCleanerManager() { return cleanerManager; }
}