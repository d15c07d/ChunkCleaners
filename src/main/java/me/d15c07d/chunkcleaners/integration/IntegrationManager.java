package me.d15c07d.chunkcleaners.integration;

import me.d15c07d.chunkcleaners.ChunkCleanersPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class IntegrationManager {

    private final ChunkCleanersPlugin plugin;

    private boolean worldguardEnabled = false;
    private boolean griefPreventionEnabled = false;
    private boolean factionsEnabled = false;
    private boolean factionsUUIDDetected = false;
    private boolean massiveCoreDetected = false;

    private boolean coreProtectEnabled = false;
    private Object coreProtectPluginInstance = null;
    private Object coreProtectAPI = null;
    private final Queue<CoreProtectChunkEntry> cpQueue = new ConcurrentLinkedQueue<>();
    private BukkitRunnable cpFlushTask = null;

    // cached WG container (reflection)
    private Object wgRegionContainer = null;

    // cached GP objects (reflection)
    private Object gpInstance = null;
    private Object gpDataStore = null;

    public IntegrationManager(ChunkCleanersPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        Plugin wgPlugin = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        Plugin gpPlugin = plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
        Plugin factionsPlugin = plugin.getServer().getPluginManager().getPlugin("Factions");
        Plugin massiveCorePlugin = plugin.getServer().getPluginManager().getPlugin("MassiveCore");
        Plugin coreProtectPlugin = plugin.getServer().getPluginManager().getPlugin("CoreProtect");

        boolean wgConfig = plugin.getConfig().getBoolean("integrations.worldguard", true);
        boolean gpConfig = plugin.getConfig().getBoolean("integrations.griefprevention", true);
        boolean factionsConfig = plugin.getConfig().getBoolean("integrations.factions", true);
        boolean coreProtectConfig = plugin.getConfig().getBoolean("integrations.coreprotect", true);

        // WorldGuard
        this.worldguardEnabled = (wgPlugin != null && wgPlugin.isEnabled() && wgConfig);
        if (this.worldguardEnabled) {
            try {
                Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
                Method getInstance = worldGuardClass.getMethod("getInstance");
                Object wg = getInstance.invoke(null);
                Method getPlatform = worldGuardClass.getMethod("getPlatform");
                Object platform = getPlatform.invoke(wg);
                Method getRegionContainer = platform.getClass().getMethod("getRegionContainer");
                Object regionContainer = getRegionContainer.invoke(platform);
                this.wgRegionContainer = regionContainer;
                plugin.getLogger().info("WorldGuard 7.x integration enabled.");
            } catch (Throwable t) {
                this.worldguardEnabled = false;
                plugin.getLogger().log(Level.WARNING, "WorldGuard integration failed to initialize: " + t.getMessage(), t);
            }
        } else {
            plugin.getLogger().info("WorldGuard integration disabled or not present.");
        }

        // GriefPrevention
        this.griefPreventionEnabled = (gpPlugin != null && gpPlugin.isEnabled() && gpConfig);
        if (this.griefPreventionEnabled) {
            try {
                Class<?> gpClass = Class.forName("me.ryanhamshire.griefprevention.GriefPrevention");
                Object instance = null;
                try {
                    instance = gpClass.getField("instance").get(null);
                } catch (NoSuchFieldException nsf) {
                    try {
                        Method getInst = gpClass.getMethod("instance");
                        instance = getInst.invoke(null);
                    } catch (NoSuchMethodException ex) {
                        try {
                            Method getInst2 = gpClass.getMethod("getInstance");
                            instance = getInst2.invoke(null);
                        } catch (NoSuchMethodException ignore) { /* leave null */ }
                    }
                }
                this.gpInstance = instance;
                if (instance != null) {
                    try {
                        this.gpDataStore = gpClass.getField("dataStore").get(instance);
                    } catch (Throwable ignored) {
                        try {
                            Method dsMethod = gpClass.getMethod("dataStore");
                            this.gpDataStore = dsMethod.invoke(instance);
                        } catch (Throwable ignore) { /* leave null */ }
                    }
                }
                plugin.getLogger().info("GriefPrevention integration enabled.");
            } catch (Throwable t) {
                this.griefPreventionEnabled = false;
                plugin.getLogger().log(Level.WARNING, "GriefPrevention integration failed to initialize: " + t.getMessage(), t);
            }
        } else {
            plugin.getLogger().info("GriefPrevention integration disabled or not present.");
        }

        // Factions (FactionsUUID / MassiveCore detection)
        this.factionsEnabled = (factionsPlugin != null && factionsPlugin.isEnabled() && factionsConfig)
                || (massiveCorePlugin != null && massiveCorePlugin.isEnabled() && factionsConfig);

        if (this.factionsEnabled) {
            try {
                Class.forName("com.massivecraft.factions.P");
                factionsUUIDDetected = true;
                plugin.getLogger().info("Detected Factions (FactionsUUID) classes.");
            } catch (Throwable ignored) {
                factionsUUIDDetected = false;
            }
            try {
                Class.forName("com.massivecraft.massivecore.MassiveCore");
                massiveCoreDetected = true;
                plugin.getLogger().info("Detected MassiveCore classes.");
            } catch (Throwable ignored) {
                massiveCoreDetected = false;
            }
            if (!factionsUUIDDetected && !massiveCoreDetected) {
                plugin.getLogger().info("Factions integration requested but no supported Factions classes detected; will act conservatively.");
            } else {
                plugin.getLogger().info("Factions integration enabled.");
            }
        } else {
            plugin.getLogger().info("Factions integration disabled or not present.");
        }

        // CoreProtect (batch-per-chunk)
        this.coreProtectEnabled = (coreProtectPlugin != null && coreProtectPlugin.isEnabled() && coreProtectConfig) &&
                plugin.getConfig().getBoolean("coreprotect.enabled", true);
        if (this.coreProtectEnabled) {
            try {
                this.coreProtectPluginInstance = coreProtectPlugin;
                try {
                    Method getAPI = coreProtectPlugin.getClass().getMethod("getAPI");
                    try {
                        this.coreProtectAPI = getAPI.invoke(coreProtectPlugin);
                    } catch (Throwable ignored) {
                        this.coreProtectAPI = null;
                    }
                } catch (NoSuchMethodException nsme) {
                    this.coreProtectAPI = null;
                }

                // schedule periodic flush
                int flushTicks = Math.max(1, plugin.getConfig().getInt("coreprotect.flush_interval_ticks", 20));
                int maxPerFlush = Math.max(1, plugin.getConfig().getInt("coreprotect.max_entries_per_flush", 50));
                int queueMax = Math.max(100, plugin.getConfig().getInt("coreprotect.queue_max_size", 5000));
                int queueTrimTo = Math.max(100, plugin.getConfig().getInt("coreprotect.queue_trim_to", 4000));

                this.cpFlushTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (cpQueue.isEmpty()) return;
                        int processed = 0;
                        while (processed < maxPerFlush) {
                            CoreProtectChunkEntry e = cpQueue.poll();
                            if (e == null) break;
                            try {
                                tryLogChunkEntry(e);
                            } catch (Throwable t) {
                                plugin.getLogger().fine("CoreProtect chunk log attempt failed: " + t.getMessage());
                            }
                            processed++;
                        }
                        if (cpQueue.size() > queueMax) {
                            int toDrop = cpQueue.size() - queueTrimTo;
                            for (int i = 0; i < toDrop; i++) cpQueue.poll();
                            plugin.getLogger().warning("CoreProtect queue exceeded max size; dropped " + toDrop + " entries.");
                        }
                    }
                };
                this.cpFlushTask.runTaskTimer(plugin, flushTicks, flushTicks);
                plugin.getLogger().info("CoreProtect integration enabled (batch-per-chunk). Flush every " + flushTicks + " ticks.");
            } catch (Throwable t) {
                this.coreProtectEnabled = false;
                plugin.getLogger().log(Level.WARNING, "CoreProtect integration failed to initialize: " + t.getMessage(), t);
            }
        } else {
            plugin.getLogger().info("CoreProtect integration disabled or not present.");
        }
    }

    /**
     * Check if player may place at the location. Uses WG/GP/Factions conservative checks.
     */
    public boolean canPlaceInRegion(Player p, Location loc) {
        Objects.requireNonNull(p, "player");
        Objects.requireNonNull(loc, "location");

        // WorldGuard
        if (worldguardEnabled && wgRegionContainer != null) {
            try {
                Object regionQuery = wgRegionContainer.getClass().getMethod("createQuery").invoke(wgRegionContainer);
                Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Object weLocation = bukkitAdapter.getMethod("adapt", Location.class).invoke(null, loc);
                Object localPlayer = bukkitAdapter.getMethod("adapt", Player.class).invoke(null, p);
                Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
                Object buildFlag = flagsClass.getField("BUILD").get(null);
                try {
                    Method testState = regionQuery.getClass().getMethod("testState", Class.forName("com.sk89q.worldedit.util.Location"), Class.forName("com.sk89q.worldguard.protection.LocalPlayer"), Class.forName("com.sk89q.worldguard.protection.flags.StateFlag"));
                    Boolean allowed = (Boolean) testState.invoke(regionQuery, weLocation, localPlayer, buildFlag);
                    if (allowed != null && !allowed) return false;
                } catch (Throwable ignored) {
                    Method testStateAlt = regionQuery.getClass().getMethod("testState", Class.forName("com.sk89q.worldedit.math.BlockVector3"), Class.forName("com.sk89q.worldguard.protection.LocalPlayer"), Class.forName("com.sk89q.worldguard.protection.flags.StateFlag"));
                    Method asBV = bukkitAdapter.getMethod("asBlockVector", Location.class);
                    Object bv = asBV.invoke(null, loc);
                    Object localPlayerAlt = bukkitAdapter.getMethod("adapt", Player.class).invoke(null, p);
                    Boolean allowed = (Boolean) testStateAlt.invoke(regionQuery, bv, localPlayerAlt, buildFlag);
                    if (allowed != null && !allowed) return false;
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "WorldGuard check failed, denying placement: " + t.getMessage(), t);
                return false;
            }
        }

        // GriefPrevention
        if (griefPreventionEnabled) {
            try {
                if (gpDataStore == null && gpInstance != null) {
                    try {
                        gpDataStore = gpInstance.getClass().getField("dataStore").get(gpInstance);
                    } catch (Throwable ignored) {
                        try {
                            Method dsMethod = gpInstance.getClass().getMethod("dataStore");
                            gpDataStore = dsMethod.invoke(gpInstance);
                        } catch (Throwable ignore) {}
                    }
                }
                if (gpDataStore != null) {
                    Method getClaimAt = null;
                    for (Method m : gpDataStore.getClass().getMethods()) {
                        if (m.getName().equals("getClaimAt")) {
                            getClaimAt = m;
                            break;
                        }
                    }
                    if (getClaimAt != null) {
                        Object claim = null;
                        try {
                            claim = getClaimAt.invoke(gpDataStore, loc, false, null);
                        } catch (IllegalArgumentException iae) {
                            try {
                                claim = getClaimAt.invoke(gpDataStore, loc);
                            } catch (Throwable ignore) {}
                        }
                        if (claim != null) {
                            boolean allowed = false;
                            try {
                                Method allowBuild = claim.getClass().getMethod("allowBuild", Player.class);
                                Object res = allowBuild.invoke(claim, p);
                                if (res instanceof Boolean && (Boolean) res) allowed = true;
                            } catch (Throwable ignore) {}
                            try {
                                Method isTrusted = claim.getClass().getMethod("isUserTrusted", String.class);
                                Object res = isTrusted.invoke(claim, p.getUniqueId().toString());
                                if (res instanceof Boolean && (Boolean) res) allowed = true;
                            } catch (Throwable ignore) {}
                            try {
                                Method getOwnerID = claim.getClass().getMethod("getOwnerID");
                                Object ownerId = getOwnerID.invoke(claim);
                                if (ownerId != null) {
                                    if (ownerId instanceof String && ownerId.equals(p.getUniqueId().toString())) allowed = true;
                                    else if (ownerId instanceof UUID && ownerId.equals(p.getUniqueId())) allowed = true;
                                }
                            } catch (Throwable ignore) {}
                            if (!allowed) return false;
                        }
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "GriefPrevention check failed, denying placement: " + t.getMessage(), t);
                return false;
            }
        }

        // Factions checks (FactionsUUID / MassiveCore)
        if (factionsEnabled) {
            try {
                if (factionsUUIDDetected) {
                    try {
                        Class<?> boardClass = Class.forName("com.massivecraft.factions.Board");
                        Method getInstance = boardClass.getMethod("getInstance");
                        Object board = getInstance.invoke(null);

                        Class<?> fLocationClass = Class.forName("com.massivecraft.factions.FLocation");
                        Object fLoc = fLocationClass.getConstructor(Location.class).newInstance(loc);

                        Method getFactionAt = boardClass.getMethod("getFactionAt", fLocationClass);
                        Object factionAt = getFactionAt.invoke(board, fLoc);

                        Class<?> fPlayersClass = Class.forName("com.massivecraft.factions.FPlayers");
                        Method fPlayersGet = fPlayersClass.getMethod("getInstance");
                        Object fPlayers = fPlayersGet.invoke(null);
                        Class<?> fPlayerClass = Class.forName("com.massivecraft.factions.FPlayer");
                        Method getByPlayer = fPlayersClass.getMethod("getByPlayer", Player.class);
                        Object fplayer = getByPlayer.invoke(fPlayers, p);
                        Method getFaction = fPlayerClass.getMethod("getFaction");
                        Object playerFaction = getFaction.invoke(fplayer);

                        if (factionAt != null && playerFaction != null && !factionAt.equals(playerFaction)) {
                            plugin.getLogger().finest("FactionsUUID check: player faction differs - deny");
                            return false;
                        }
                    } catch (Throwable inner) {
                        plugin.getLogger().finest("FactionsUUID check failed: " + inner.getMessage());
                    }
                }

                if (massiveCoreDetected) {
                    try {
                        Class<?> boardClass = Class.forName("com.massivecraft.factions.Board");
                        Method getInstance = boardClass.getMethod("getInstance");
                        Object board = getInstance.invoke(null);
                        Class<?> fLocationClass = Class.forName("com.massivecraft.factions.FLocation");
                        Object fLoc = fLocationClass.getConstructor(Location.class).newInstance(loc);
                        Method getFactionAt = boardClass.getMethod("getFactionAt", fLocationClass);
                        Object factionAt = getFactionAt.invoke(board, fLoc);

                        Class<?> fPlayersClass = Class.forName("com.massivecraft.factions.FPlayers");
                        Method fPlayersGet = fPlayersClass.getMethod("getInstance");
                        Object fPlayers = fPlayersGet.invoke(null);
                        Method getByPlayer = fPlayersClass.getMethod("getByPlayer", Player.class);
                        Object fp = getByPlayer.invoke(fPlayers, p);
                        Class<?> fPlayerClass = Class.forName("com.massivecraft.factions.FPlayer");
                        Method getFaction = fPlayerClass.getMethod("getFaction");
                        Object playerFaction = getFaction.invoke(fp);

                        if (factionAt != null && playerFaction != null && !factionAt.equals(playerFaction)) {
                            plugin.getLogger().finest("MassiveCore Factions check: player faction differs - deny");
                            return false;
                        }
                    } catch (Throwable inner) {
                        plugin.getLogger().finest("MassiveCore check failed: " + inner.getMessage());
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "Factions integration check failed — denying placement: " + t.getMessage(), t);
                return false;
            }
        }

        // allowed by default if no protection denies
        return true;
    }

    /**
     * Conservative check for editing a whole chunk on behalf of ownerUuid.
     * If protections exist and owner is offline, returns false.
     */
    public boolean canEditChunk(UUID ownerUuid, Location loc) {
        if (!worldguardEnabled && !griefPreventionEnabled && !factionsEnabled) return true;
        Player owner = ownerUuid == null ? null : Bukkit.getPlayer(ownerUuid);
        if (owner == null || !owner.isOnline()) {
            return false;
        }
        return canPlaceInRegion(owner, loc);
    }

    /* ---------------- CoreProtect chunk-summary API ---------------- */

    /**
     * Enqueue a chunk-summary entry for CoreProtect.
     * Called from main thread when a chunk's cleaning is completed (or periodically when a chunk segment is finished).
     *
     * @param actorUuid owner on whose behalf cleaning ran (may be null)
     * @param chunkCenter representative Location for the chunk (usually center)
     * @param totalRemoved total blocks removed in this chunk by the cleaner
     * @param breakdown map of Material -> count (may be empty or null)
     */
    public void enqueueChunkSummary(UUID actorUuid, Location chunkCenter, int totalRemoved, Map<Material, Integer> breakdown) {
        if (!coreProtectEnabled) return;
        if (chunkCenter == null) return;
        if (totalRemoved <= 0) return;

        int queueMax = Math.max(100, plugin.getConfig().getInt("coreprotect.queue_max_size", 5000));
        if (cpQueue.size() >= queueMax) {
            // drop to avoid memory blowup
            return;
        }

        String actorName = (actorUuid == null) ? "ChunkCleaner" : (Bukkit.getOfflinePlayer(actorUuid).getName());
        if (actorName == null) actorName = "ChunkCleaner";

        cpQueue.add(new CoreProtectChunkEntry(actorName, chunkCenter.clone(), totalRemoved, breakdown));
    }

    /**
     * Attempt to write a chunk-summary entry to CoreProtect. Tries several reflective APIs; if none exist,
     * falls back to a conservative server log to ensure there's some record.
     */
    private void tryLogChunkEntry(CoreProtectChunkEntry e) {
        if (coreProtectAPI == null || e == null) return;
        try {
            Block block = e.chunkCenter.getBlock();
            Class<?> apiClass = coreProtectAPI.getClass();

            // Try (String playerName, Block block) style "logRemoval" or any log* method with Block param
            for (Method m : apiClass.getMethods()) {
                if (!m.getName().toLowerCase().contains("log")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2 && params[0] == String.class && Block.class.isAssignableFrom(params[1])) {
                    try {
                        m.invoke(coreProtectAPI, e.actorName, block);
                        return;
                    } catch (Throwable ignored) { /* try next */ }
                }
            }

            // Try variant (String playerName, Location loc, Material material) - log a representative Material (AIR)
            for (Method m : apiClass.getMethods()) {
                if (!m.getName().toLowerCase().contains("log")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length >= 2 && params[0] == String.class && params[1] == Location.class) {
                    try {
                        // prefer to pass Material.AIR to represent mass removals
                        if (params.length >= 3 && params[2] == Material.class) {
                            m.invoke(coreProtectAPI, e.actorName, e.chunkCenter, Material.AIR);
                            return;
                        } else {
                            m.invoke(coreProtectAPI, e.actorName, e.chunkCenter);
                            return;
                        }
                    } catch (Throwable ignored) { /* try next */ }
                }
            }

            // As a last attempt, try any method whose first parameter is String and second is something assignable from Location or Block
            for (Method m : apiClass.getMethods()) {
                if (!m.getName().toLowerCase().contains("log")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length >= 2 && params[0] == String.class) {
                    try {
                        if (params[1] == Location.class) {
                            m.invoke(coreProtectAPI, e.actorName, e.chunkCenter);
                            return;
                        } else if (Block.class.isAssignableFrom(params[1])) {
                            m.invoke(coreProtectAPI, e.actorName, e.chunkCenter.getBlock());
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("CoreProtect chunk logging reflective attempt failed: " + t.getMessage());
        }

        // Fallback: server-side info log so admins can inspect. This avoids spamming CoreProtect but still records activity.
        plugin.getLogger().info("CoreProtect: chunk cleaned by " + e.actorName + " at " + formatLocation(e.chunkCenter)
                + " removed=" + e.totalRemoved + (e.breakdown != null ? " breakdown=" + e.breakdown : ""));
    }

    public void shutdown() {
        if (cpFlushTask != null) {
            try { cpFlushTask.cancel(); } catch (Throwable ignored) {}
            cpFlushTask = null;
        }
    }

    private static String formatLocation(Location l) {
        if (l == null) return "unknown";
        return l.getWorld().getName() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    /* CoreProtect queue entry (chunk-summary) */
    private static class CoreProtectChunkEntry {
        final String actorName;
        final Location chunkCenter;
        final int totalRemoved;
        final Map<Material, Integer> breakdown;

        CoreProtectChunkEntry(String actorName, Location chunkCenter, int totalRemoved, Map<Material, Integer> breakdown) {
            this.actorName = actorName;
            this.chunkCenter = chunkCenter;
            this.totalRemoved = totalRemoved;
            this.breakdown = breakdown;
        }
    }
}