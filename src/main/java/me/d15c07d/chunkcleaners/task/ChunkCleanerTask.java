package me.d15c07d.chunkcleaners.task;

import me.d15c07d.chunkcleaners.ChunkCleanersPlugin;
import me.d15c07d.chunkcleaners.utils.ActionBarUtil;
import me.d15c07d.chunkcleaners.utils.HologramUtil;
import me.d15c07d.chunkcleaners.utils.MessageUtil;
import org.bukkit.*;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChunkCleanerTask {

    private final UUID id;
    private final UUID ownerUuid;
    private final String ownerName;
    private final int originChunkX;
    private final int originChunkZ;
    private final String worldName;
    private final String typeKey;
    private final int size;
    private final int durationSeconds;
    private final ChunkCleanersPlugin plugin;

    // Block coordinates where the cleaner item was placed (used to anchor hologram)
    private final int placedBlockX;
    private final int placedBlockY;
    private final int placedBlockZ;

    // Runtime state
    private long startedAt;
    private volatile boolean cancelled = false;

    // Ordered list of chunk coords to process ([chunkX,chunkZ])
    private final List<int[]> chunkCoords = new ArrayList<>();
    private volatile int currentChunkIndex = 0;
    private volatile int currentY = 0; // next Y to process (top-down)

    // Y bounds
    private int minY;
    private int maxY; // exclusive

    // Work accounting (levels)
    private long totalLevels = 0L;
    private long processedLevels = 0L;

    // ETA smoothing samples (main-thread only)
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final int sampleWindowSeconds;

    private static class Sample {
        final long timestampMillis;
        final int levels;
        Sample(long timestampMillis, int levels) { this.timestampMillis = timestampMillis; this.levels = levels; }
    }

    // Scheduler handle
    private BukkitTask schedulerTask;

    // Hologram handle
    private HologramUtil.HologramHandle hologramHandle;

    // Adaptive knobs (runtime)
    private volatile int currentChunksPerInterval;
    private volatile int currentYBatchSize;

    // Adaptive TPS sampling
    private int tpsCheckTickCounter = 0;

    // Per-chunk accumulation for summaries
    private final Map<String, Integer> removedCountByChunk = new HashMap<>();
    private final Map<String, Map<Material, Integer>> removedMaterialsByChunk = new HashMap<>();

    /**
     * Constructor (includes placed block coords so hologram can be anchored exactly).
     */
    public ChunkCleanerTask(UUID id,
                            UUID ownerUuid,
                            String ownerName,
                            int originChunkX,
                            int originChunkZ,
                            String worldName,
                            String typeKey,
                            int size,
                            int durationSeconds,
                            ChunkCleanersPlugin plugin,
                            int placedBlockX,
                            int placedBlockY,
                            int placedBlockZ) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.originChunkX = originChunkX;
        this.originChunkZ = originChunkZ;
        this.worldName = worldName;
        this.typeKey = typeKey;
        this.size = Math.max(1, size);
        this.durationSeconds = Math.max(1, durationSeconds);
        this.plugin = plugin;
        this.startedAt = Instant.now().getEpochSecond();

        this.placedBlockX = placedBlockX;
        this.placedBlockY = placedBlockY;
        this.placedBlockZ = placedBlockZ;

        buildChunkList();

        // sample window for ETA smoothing (seconds)
        this.sampleWindowSeconds = Math.max(3, plugin.getConfig().getInt("performance.eta_window_seconds", 8));

        // baseline runtime knobs (may be adjusted in start())
        this.currentChunksPerInterval = Math.max(1, plugin.getConfig().getInt("performance.max_chunks_per_interval", 1));
        this.currentYBatchSize = Math.max(1, plugin.getConfig().getInt("performance.y_batch_size", 1));
    }

    /* ---------------- Accessors (for persistence / external use) ---------------- */

    public UUID getId() { return id; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public int getChunkX() { return originChunkX; }
    public int getChunkZ() { return originChunkZ; }
    public String getWorldName() { return worldName; }
    public String getTypeKey() { return typeKey; }
    public int getSize() { return size; }
    public int getDurationSeconds() { return durationSeconds; }
    public long getStartedAt() { return startedAt; }

    public int getCurrentChunkIndex() { return currentChunkIndex; }
    public int getCurrentY() { return currentY; }

    public int getPlacedBlockX() { return placedBlockX; }
    public int getPlacedBlockY() { return placedBlockY; }
    public int getPlacedBlockZ() { return placedBlockZ; }

    public void setCurrentChunkIndex(int idx) { this.currentChunkIndex = Math.max(0, Math.min(idx, Math.max(0, chunkCoords.size()))); }
    public void setCurrentY(int y) { this.currentY = y; }
    public void setStartedAt(long ts) { this.startedAt = ts; }

    /* ---------------- Internal helpers ---------------- */

    private void buildChunkList() {
        int half = size / 2;
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int cx = originChunkX + (dx - half);
                int cz = originChunkZ + (dz - half);
                chunkCoords.add(new int[] { cx, cz });
            }
        }
    }

    /**
     * Start or resume the cleaner.
     */
    public void start() {
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            plugin.getLogger().warning("ChunkCleanerTask start(): world not found: " + worldName);
            cancel();
            return;
        }

        minY = Math.max(w.getMinHeight(), -63);
        maxY = w.getMaxHeight();
        final int topY = maxY - 1;

        if (currentY == 0) currentY = topY;

        long levelsPerChunk = Math.max(0, maxY - minY);
        totalLevels = (long) chunkCoords.size() * levelsPerChunk;
        if (levelsPerChunk > 0) {
            long processedBefore = (long) currentChunkIndex * levelsPerChunk;
            long processedInCurrent = Math.max(0, (topY - currentY));
            processedLevels = processedBefore + processedInCurrent;
        } else {
            processedLevels = 0;
        }

        // Spawn hologram anchored above the placed block (on main thread)
        try {
            if (plugin.getConfig().getBoolean("hologram.enabled", true)) {
                double offset = plugin.getConfig().getDouble("hologram.offset", 0.5);
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Location base = new Location(world, placedBlockX + 0.5, placedBlockY + 1.0 + offset, placedBlockZ + 0.5);
                    List<String> cfgLines = plugin.getConfig().getStringList("hologram.lines");
                    final List<String> lines;
                    if (cfgLines == null || cfgLines.isEmpty()) {
                        String ht = plugin.getConfig().getString("hologram.text", "Cleaning: {remaining}s");
                        lines = Arrays.asList(ht.split("\\r?\\n"));
                    } else {
                        lines = new ArrayList<>(cfgLines);
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            hologramHandle = HologramUtil.createHologram(base, lines);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("Failed to spawn hologram: " + t.getMessage());
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}

        // Baseline knobs and size-aware scaling
        final int baseChunksConfig = Math.max(1, plugin.getConfig().getInt("performance.max_chunks_per_interval", 1));
        final int baseYBatchConfig = Math.max(1, plugin.getConfig().getInt("performance.y_batch_size", 1));

        final boolean sizeScaleEnabled = plugin.getConfig().getBoolean("performance.size_scale_enabled", true);
        final int sizeScaleMultiplier = Math.max(1, plugin.getConfig().getInt("performance.size_scale_multiplier", 1));
        final int sizeScaleCap = Math.max(1, plugin.getConfig().getInt("performance.size_scale_cap", 8));
        int scaleFactor = 1;
        if (sizeScaleEnabled && this.size > 1) {
            scaleFactor = Math.min(sizeScaleCap, this.size * sizeScaleMultiplier);
        }
        final int baselineChunks = Math.max(1, baseChunksConfig * scaleFactor);
        final int baselineYBatch = Math.max(1, baseYBatchConfig * scaleFactor);

        final long ticksPerChunkComputed = Math.max(1, Math.round(((double) durationSeconds / Math.max(1, chunkCoords.size())) * 20.0));
        final long defaultTicks = Math.max(1, plugin.getConfig().getInt("performance.ticks_per_chunk_interval", 1));
        long scheduleInterval = Math.max(1, Math.min(ticksPerChunkComputed, defaultTicks));
        final int aggressiveIntervalDivisor = Math.max(1, plugin.getConfig().getInt("performance.aggressive_interval_divisor", 1));
        if (sizeScaleEnabled && this.size > 1 && aggressiveIntervalDivisor > 1) {
            scheduleInterval = Math.max(1, scheduleInterval / Math.min(aggressiveIntervalDivisor, Math.max(1, this.size)));
        }

        // TPS-adaptive config
        final double tpsThreshold = plugin.getConfig().getDouble("performance.tps_threshold", 18.0);
        final int tpsMinChunks = Math.max(1, plugin.getConfig().getInt("performance.tps_min_chunks_per_interval", 1));
        final int tpsMinYBatch = Math.max(1, plugin.getConfig().getInt("performance.tps_min_y_batch_size", 1));
        final double tpsSmoothing = plugin.getConfig().getDouble("performance.tps_smoothing", 0.75);
        final int tpsCheckInterval = Math.max(1, plugin.getConfig().getInt("performance.tps_check_interval_ticks", 1));

        this.currentChunksPerInterval = baselineChunks;
        this.currentYBatchSize = baselineYBatch;

        // Start async scheduler preparing main-thread jobs
        this.schedulerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (cancelled) return;

            // Adaptive TPS scaling
            if (++tpsCheckTickCounter >= tpsCheckInterval) {
                tpsCheckTickCounter = 0;
                double tps = getServerTPS();
                if (tps < tpsThreshold) {
                    double factor = Math.max(0.0, Math.min(1.0, tps / tpsThreshold));
                    double smoothed = (tpsSmoothing * factor) + ((1.0 - tpsSmoothing) * 1.0);
                    int newChunks = Math.max(tpsMinChunks, (int) Math.max(1, Math.round(baselineChunks * smoothed)));
                    int newYBatch = Math.max(tpsMinYBatch, (int) Math.max(1, Math.round(baselineYBatch * smoothed)));
                    currentChunksPerInterval = newChunks;
                    currentYBatchSize = newYBatch;
                } else {
                    currentChunksPerInterval = baselineChunks;
                    currentYBatchSize = baselineYBatch;
                }
            }

            if (currentChunkIndex >= chunkCoords.size()) {
                finish();
                return;
            }

            Queue<Runnable> mainThreadJobs = new ConcurrentLinkedQueue<>();

            for (int i = 0; i < currentChunksPerInterval && currentChunkIndex < chunkCoords.size(); i++) {
                final int[] coords = chunkCoords.get(currentChunkIndex);
                final int startY = currentY;
                final int endYInclusive = Math.max(minY, startY - currentYBatchSize + 1);

                // Protection check
                Location chunkCenter = getCenterLocation(coords[0], coords[1]);
                if (!plugin.getIntegrationManager().canEditChunk(ownerUuid, chunkCenter)) {
                    // skip this chunk
                    currentChunkIndex++;
                    currentY = maxY - 1;
                    continue;
                }

                mainThreadJobs.add(() -> {
                    if (cancelled) return;
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) return;
                    Chunk c = world.getChunkAt(coords[0], coords[1]);

                    int removedThisJob = 0;
                    Map<Material, Integer> removedMaterialsThisJob = new HashMap<>();

                    for (int y = startY; y >= endYInclusive; y--) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                org.bukkit.block.Block block = c.getBlock(x, y, z);
                                Material m = block.getType();
                                if (m != Material.AIR && m != Material.BEDROCK) {
                                    removedThisJob++;
                                    removedMaterialsThisJob.merge(m, 1, Integer::sum);
                                    block.setType(Material.AIR, false);
                                }
                            }
                        }
                    }

                    // accumulate per-chunk
                    String key = coords[0] + "," + coords[1];
                    removedCountByChunk.merge(key, removedThisJob, Integer::sum);
                    removedMaterialsByChunk.computeIfAbsent(key, k -> new HashMap<>());
                    Map<Material, Integer> agg = removedMaterialsByChunk.get(key);
                    for (Map.Entry<Material, Integer> ent : removedMaterialsThisJob.entrySet()) {
                        agg.merge(ent.getKey(), ent.getValue(), Integer::sum);
                    }

                    int levelsProcessed = (startY - endYInclusive + 1);
                    processedLevels += levelsProcessed;

                    // record a sample for ETA smoothing
                    if (levelsProcessed > 0) {
                        long now = System.currentTimeMillis();
                        samples.addLast(new Sample(now, levelsProcessed));
                        long cutoff = now - (sampleWindowSeconds * 1000L);
                        while (!samples.isEmpty() && samples.peekFirst().timestampMillis < cutoff) samples.removeFirst();
                    }

                    // If chunk finished
                    if (endYInclusive <= minY) {
                        Arrays.stream(c.getEntities()).forEach(e -> {
                            if (!(e instanceof Player)) e.remove();
                        });

                        int totalRemoved = removedCountByChunk.getOrDefault(key, 0);
                        Map<Material, Integer> breakdown = removedMaterialsByChunk.getOrDefault(key, Map.of());

                        try {
                            plugin.getIntegrationManager().enqueueChunkSummary(ownerUuid, chunkCenter, totalRemoved, breakdown);
                        } catch (Throwable t) {
                            plugin.getLogger().fine("Failed to enqueue CoreProtect chunk summary: " + t.getMessage());
                        }

                        // persist progress immediately (main thread)
                        try {
                            plugin.getPersistenceManager().saveTaskProgress(this);
                        } catch (Throwable t) {
                            plugin.getLogger().fine("Failed to persist task progress: " + t.getMessage());
                        }

                        removedCountByChunk.remove(key);
                        removedMaterialsByChunk.remove(key);
                    }
                });

                // advance Y pointer
                currentY = endYInclusive - 1;
                if (currentY < minY) {
                    currentChunkIndex++;
                    currentY = maxY - 1;
                }
            }

            // execute main-thread jobs
            if (!mainThreadJobs.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Runnable job;
                    while ((job = mainThreadJobs.poll()) != null) {
                        try { job.run(); } catch (Throwable ex) { plugin.getLogger().warning("ChunkCleaner job failed: " + ex.getMessage()); }
                    }
                    updateVisuals();
                });
            } else {
                Bukkit.getScheduler().runTask(plugin, this::updateVisuals);
            }
        }, 0L, Math.max(1, scheduleInterval));
    }

    /**
     * Resume = start (state restored by setters before calling resume).
     */
    public void resume() { start(); }

    /**
     * Cancel and cleanup.
     */
    public void cancel() {
        cancelled = true;
        if (schedulerTask != null) schedulerTask.cancel();
        if (hologramHandle != null) {
            try { hologramHandle.remove(); } catch (Throwable ignored) {}
        }
        plugin.getCleanerManager().removeTask(id);
    }

    private void finish() {
        cancelled = true;
        if (schedulerTask != null) schedulerTask.cancel();
        if (hologramHandle != null) {
            try { hologramHandle.remove(); } catch (Throwable ignored) {}
        }
        plugin.getCleanerManager().removeTask(id);
        Player p = Bukkit.getPlayer(ownerUuid);
        if (p != null && p.isOnline()) {
            p.sendMessage(MessageUtil.parse(plugin.getConfig().getString("messages.finish_message", "<green>Your chunk cleaner has finished clearing the area.</green>")));
        }
    }

    /**
     * Update actionbar and hologram visuals. Always called on main thread.
     */
    private void updateVisuals() {
        double progress = 0.0;
        if (totalLevels > 0) progress = Math.min(1.0, Math.max(0.0, processedLevels / (double) totalLevels));

        long remainingSeconds;
        if (processedLevels >= totalLevels) {
            remainingSeconds = 0;
        } else {
            // compute smoothed rate from samples
            double windowLevels = 0.0;
            long now = System.currentTimeMillis();
            long cutoff = now - (sampleWindowSeconds * 1000L);
            for (Sample s : samples) {
                if (s.timestampMillis >= cutoff) windowLevels += s.levels;
            }
            double rate = windowLevels / Math.max(1.0, sampleWindowSeconds);
            if (rate <= 0.0001) {
                double remFrac = 1.0 - progress;
                double est = remFrac * durationSeconds;
                remainingSeconds = Math.max(1, (long) Math.ceil(est));
            } else {
                long remainingLevels = Math.max(0L, totalLevels - processedLevels);
                double estSeconds = remainingLevels / rate;
                remainingSeconds = Math.max(1, (long) Math.ceil(estSeconds));
            }
        }

        Player p = Bukkit.getPlayer(ownerUuid);
        if (p != null && p.isOnline()) {
            String msg = plugin.getConfig().getString("actionbar.message", "&aChunk Cleaner &7- &e{remaining}s &8[&e{progress}%&8]");
            msg = msg.replace("{remaining}", String.valueOf(remainingSeconds))
                    .replace("{progress}", String.format("%.0f", progress * 100.0));
            ActionBarUtil.sendActionBar(p, MessageUtil.parse(msg));
        }

        if (hologramHandle != null) {
            try {
                List<String> cfgLines = plugin.getConfig().getStringList("hologram.lines");
                if (cfgLines == null || cfgLines.isEmpty()) {
                    String ht = plugin.getConfig().getString("hologram.text", "Cleaning: {remaining}s");
                    cfgLines = Arrays.asList(ht.split("\\r?\\n"));
                }
                List<String> out = new ArrayList<>();
                for (String t : cfgLines) {
                    out.add(t.replace("{remaining}", String.valueOf(remainingSeconds))
                            .replace("{progress}", String.format("%.0f", progress * 100.0)));
                }
                hologramHandle.setLines(out);
            } catch (Throwable ignored) {}
        }
    }

    public void onConfigReload() {
        this.currentChunksPerInterval = Math.max(1, plugin.getConfig().getInt("performance.max_chunks_per_interval", 1));
        this.currentYBatchSize = Math.max(1, plugin.getConfig().getInt("performance.y_batch_size", 1));
        updateVisuals();
    }

    /* ---------------- Utilities ---------------- */

    private Location getCenterLocation() { return getCenterLocation(originChunkX, originChunkZ); }

    private Location getCenterLocation(int chunkX, int chunkZ) {
        World w = Bukkit.getWorld(worldName);
        int blockX = (chunkX << 4) + 8;
        int blockZ = (chunkZ << 4) + 8;
        int y = 64;
        if (w != null) y = Math.max(64, w.getHighestBlockYAt(blockX, blockZ));
        return new Location(w, blockX, y, blockZ);
    }

    /**
     * Query server TPS reflectively when available; return 20.0 fallback if not.
     */
    private double getServerTPS() {
        try {
            Object server = Bukkit.getServer();
            Method m = server.getClass().getMethod("getTPS");
            Object v = m.invoke(server);
            if (v instanceof double[]) {
                double[] arr = (double[]) v;
                if (arr.length > 0) {
                    double sum = 0.0;
                    for (double d : arr) sum += d;
                    return Math.max(0.0, Math.min(20.0, sum / arr.length));
                }
            } else if (v instanceof Double) {
                return Math.max(0.0, Math.min(20.0, (Double) v));
            }
        } catch (NoSuchMethodException ignore) {
            // fallthrough
        } catch (Throwable t) {
            plugin.getLogger().fine("Could not query server TPS: " + t.getMessage());
        }
        return 20.0;
    }
}