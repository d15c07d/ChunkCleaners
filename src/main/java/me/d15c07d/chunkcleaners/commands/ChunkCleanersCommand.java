package me.d15c07d.chunkcleaners.commands;

import me.d15c07d.chunkcleaners.ChunkCleanersPlugin;
import me.d15c07d.chunkcleaners.config.ConfigManager;
import me.d15c07d.chunkcleaners.storage.PersistenceManager;
import me.d15c07d.chunkcleaners.task.ChunkCleanerManager;
import me.d15c07d.chunkcleaners.task.ChunkCleanerTask;
import me.d15c07d.chunkcleaners.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified command handler for:
 *   /chunkcleaners (alias /cc)
 *
 * Updated give signature:
 *   /chunkcleaners give <player> <type> [amount]
 *
 * Other subcommands:
 *   list
 *   reload
 *   admin save
 *   admin savetask <uuid>
 *   admin list
 *   admin status <uuid>
 *
 * All messages are configurable under messages.main.* and messages.admin.* in config.yml.
 */
public class ChunkCleanersCommand implements CommandExecutor, TabCompleter {

    private final ChunkCleanersPlugin plugin;
    private final ChunkCleanerManager manager;
    private final PersistenceManager persistence;
    private final ConfigManager configManager;

    public ChunkCleanersCommand(ChunkCleanersPlugin plugin) {
        this.plugin = plugin;
        this.manager = plugin.getCleanerManager();
        this.persistence = manager.getPersistence();
        this.configManager = plugin.getConfigManager();

        // register primary command
        Objects.requireNonNull(plugin.getCommand("chunkcleaners"), "Command 'chunkcleaners' not defined in plugin.yml");
        plugin.getCommand("chunkcleaners").setExecutor(this);
        plugin.getCommand("chunkcleaners").setTabCompleter(this);

        // also bind alias executor if command defined in plugin.yml
        if (plugin.getCommand("cc") != null) {
            plugin.getCommand("cc").setExecutor(this);
            plugin.getCommand("cc").setTabCompleter(this);
        }
    }

    private boolean hasAdminPerm(CommandSender sender) {
        return sender.hasPermission("chunkcleaners.admin") || sender.isOp();
    }

    private String cfgMain(String path, String def) {
        return plugin.getConfig().getString("messages.main." + path, def);
    }

    private String cfgAdmin(String path, String def) {
        return plugin.getConfig().getString("messages.admin." + path, def);
    }

    private List<String> cfgAdminList(String path) {
        List<String> list = plugin.getConfig().getStringList("messages.admin." + path);
        return (list == null) ? List.of() : list;
    }

    private void sendParsed(CommandSender to, String raw) {
        to.sendMessage(MessageUtil.parse(raw));
    }

    private void sendParsedVar(CommandSender to, String template, Map<String, String> vars) {
        if (template == null) return;
        String s = template;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                s = s.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        sendParsed(to, s);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // show main help/usage configured under messages.main.usage_lines
            sendParsed(sender, cfgMain("usage_header", "<yellow>ChunkCleaners commands:"));
            for (String l : plugin.getConfig().getStringList("messages.main.usage_lines")) {
                sendParsed(sender, l);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "give":
                    return handleGive(sender, args);
                case "list":
                    return handleList(sender);
                case "reload":
                    return handleReload(sender);
                case "admin":
                    return handleAdmin(sender, args);
                default:
                    // Unknown: show help
                    sendParsed(sender, cfgMain("usage_header", "<yellow>ChunkCleaners commands:"));
                    for (String l : plugin.getConfig().getStringList("messages.main.usage_lines")) {
                        sendParsed(sender, l);
                    }
                    return true;
            }
        } catch (Throwable t) {
            sender.sendMessage(MessageUtil.parse("<red>Error: " + t.getMessage()));
            plugin.getLogger().warning("ChunkCleanersCommand failure: " + t.getMessage());
            return true;
        }
    }

    /* ---------------- Main subcommands ---------------- */

    /**
     * New give args syntax:
     *  /chunkcleaners give <player> <type>
     *  /chunkcleaners give <player> <type> <amount>
     *
     * Amount must be an integer >0. Player can be online or offline.
     */
    private boolean handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chunkcleaners.give") && !sender.isOp()) {
            sender.sendMessage(MessageUtil.parse(cfgMain("give_no_permission", "<red>You don't have permission to give cleaners.")));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(MessageUtil.parse("<red>Usage: /chunkcleaners give <player> <type> [amount]"));
            return true;
        }

        String playerName = args[1];
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        if (target == null) {
            sender.sendMessage(MessageUtil.parse("<red>Player not found: " + playerName));
            return true;
        }

        String typeKey = args[2];
        Optional<ConfigManager.CleanerType> typeOpt = configManager.getType(typeKey);
        if (typeOpt.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(plugin.getConfig().getString("messages.invalid_type", "&cThat chunk cleaner type doesn't exist.")));
            return true;
        }
        ConfigManager.CleanerType type = typeOpt.get();

        int amount = 1;
        if (args.length >= 4) {
            try { amount = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) { amount = 1; }
        }

        try {
            manager.giveCleanerItem(target, type, amount);
            sendParsedVar(sender, cfgMain("give_success", "<green>Gave {amount}x {type} to {player}."), Map.of(
                    "amount", String.valueOf(amount),
                    "type", typeKey,
                    "player", target.getName() == null ? playerName : target.getName()
            ));
        } catch (Throwable t) {
            sender.sendMessage(MessageUtil.parse("<red>Error giving cleaner: " + t.getMessage()));
            plugin.getLogger().warning("MainCommand.give failed: " + t.getMessage());
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (hasAdminPerm(sender)) {
            // reuse admin list
            return handleAdminList(sender);
        }
        int count = manager.getActiveTasks().size();
        sendParsedVar(sender, cfgMain("list_simple", "<gold>Active chunk cleaners: <white>{count}"), Map.of("count", String.valueOf(count)));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!hasAdminPerm(sender)) {
            sender.sendMessage(MessageUtil.parse(plugin.getConfig().getString("messages.no_permission", "&cYou don't have permission to do that.")));
            return true;
        }
        try {
            plugin.reloadConfig();
            try { plugin.getConfigManager().reload(); } catch (Throwable ignored) {}
            try { plugin.getCleanerManager().onConfigReload(); } catch (Throwable ignored) {}
            sendParsed(sender, cfgMain("reload_success", "<green>Configuration reloaded."));
        } catch (Throwable t) {
            sendParsedVar(sender, cfgMain("reload_fail", "<red>Failed to reload: {error}"), Map.of("error", t.getMessage() == null ? "unknown" : t.getMessage()));
            plugin.getLogger().warning("MainCommand.reload failed: " + t.getMessage());
        }
        return true;
    }

    /* ---------------- Admin subcommands (under /chunkcleaners admin ...) ---------------- */

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!hasAdminPerm(sender)) {
            sender.sendMessage(MessageUtil.parse(cfgAdmin("help_no_permission", "<red>You don't have permission to use admin commands.")));
            return true;
        }
        if (args.length == 1) {
            // show admin help
            sendParsed(sender, cfgAdmin("help_header", "<gold>ChunkCleaners Admin Commands"));
            for (String l : cfgAdminList("help_lines")) sendParsed(sender, l);
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "save":
                return handleAdminSave(sender);
            case "savetask":
                return handleAdminSaveTask(sender, args);
            case "list":
                return handleAdminList(sender);
            case "status":
                return handleAdminStatus(sender, args);
            default:
                sendParsed(sender, cfgAdmin("help_header", "<gold>ChunkCleaners Admin Commands"));
                for (String l : cfgAdminList("help_lines")) sendParsed(sender, l);
                return true;
        }
    }

    private boolean handleAdminSave(CommandSender sender) {
        try {
            persistence.save();
            sendParsed(sender, cfgAdmin("save_success", "<green>Saved all active cleaners to disk."));
        } catch (Throwable t) {
            sendParsedVar(sender, cfgAdmin("save_fail", "<red>Failed to save: {error}"), Map.of("error", t.getMessage() == null ? "unknown" : t.getMessage()));
            plugin.getLogger().warning("Admin save failed: " + t.getMessage());
        }
        return true;
    }

    private boolean handleAdminSaveTask(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendParsed(sender, cfgAdmin("savetask_invalid_uuid", "<red>Usage: /chunkcleaners admin savetask <uuid>"));
            return true;
        }
        String idStr = args[2];
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException iae) {
            sendParsedVar(sender, cfgAdmin("savetask_invalid_uuid", "<red>Invalid UUID: {id}"), Map.of("id", idStr));
            return true;
        }
        Optional<ChunkCleanerTask> opt = manager.getActiveTasks().stream().filter(t -> t.getId().equals(id)).findFirst();
        if (opt.isEmpty()) {
            sendParsed(sender, cfgAdmin("savetask_no_task", "<red>No active task with that UUID."));
            return true;
        }
        try {
            persistence.saveTaskProgress(opt.get());
            sendParsedVar(sender, cfgAdmin("savetask_success", "<green>Saved progress for task {id}"), Map.of("id", idStr));
        } catch (Throwable t) {
            sendParsedVar(sender, cfgAdmin("save_fail", "<red>Failed to save: {error}"), Map.of("error", t.getMessage() == null ? "unknown" : t.getMessage()));
            plugin.getLogger().warning("Admin saveTask failed: " + t.getMessage());
        }
        return true;
    }

    private boolean handleAdminList(CommandSender sender) {
        Collection<ChunkCleanerTask> tasks = manager.getActiveTasks();
        if (tasks.isEmpty()) {
            sendParsed(sender, cfgAdmin("list_empty", "<yellow>No active chunk cleaners."));
            return true;
        }
        sendParsedVar(sender, cfgAdmin("list_header", "<gold>Active Chunk Cleaners: <gray>({count})"), Map.of("count", String.valueOf(tasks.size())));
        int idx = 1;
        for (ChunkCleanerTask t : tasks) {
            String id = t.getId().toString();
            String owner = t.getOwnerName() == null ? "unknown" : t.getOwnerName();
            String coords = t.getWorldName() + ":" + t.getChunkX() + "," + t.getChunkZ();
            World w = Bukkit.getWorld(t.getWorldName());
            long percent = 0;
            if (w != null) {
                int minY = Math.max(w.getMinHeight(), -63);
                int maxY = w.getMaxHeight();
                long levelsPerChunk = Math.max(0, maxY - minY);
                long processedBefore = (long) t.getCurrentChunkIndex() * levelsPerChunk;
                int topY = maxY - 1;
                long processedInCurrent = Math.max(0, (topY - t.getCurrentY()));
                long processed = processedBefore + processedInCurrent;
                long total = Math.max(1, (long) t.getSize() * levelsPerChunk);
                percent = Math.min(100, (processed * 100) / total);
            }
            String entryFormat = plugin.getConfig().getString("messages.list_entry", null);
            String line;
            if (entryFormat != null && !entryFormat.isBlank()) {
                line = entryFormat.replace("{index}", String.valueOf(idx))
                        .replace("{owner}", owner)
                        .replace("{coords}", coords)
                        .replace("{type}", t.getTypeKey())
                        .replace("{age}", String.valueOf(Math.max(0, Instant.now().getEpochSecond() - t.getStartedAt())));
            } else {
                line = "<aqua>" + idx + ". <white>" + owner + " <gray>(" + coords + ") <gold>" + percent + "% <yellow>" + id;
            }
            sendParsed(sender, line);
            idx++;
        }
        return true;
    }

    private boolean handleAdminStatus(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendParsed(sender, cfgAdmin("status_not_found", "<red>Usage: /chunkcleaners admin status <uuid>"));
            return true;
        }
        String idStr = args[2];
        UUID id;
        try {
            id = UUID.fromString(idStr);
        } catch (IllegalArgumentException iae) {
            sendParsedVar(sender, cfgAdmin("savetask_invalid_uuid", "<red>Invalid UUID: {id}"), Map.of("id", idStr));
            return true;
        }
        Optional<ChunkCleanerTask> opt = manager.getActiveTasks().stream().filter(t -> t.getId().equals(id)).findFirst();
        if (opt.isEmpty()) {
            sendParsed(sender, cfgAdmin("status_not_found", "<red>No active task with that UUID."));
            return true;
        }
        ChunkCleanerTask t = opt.get();

        sendParsedVar(sender, "<gold>Task: <white>{id}", Map.of("id", t.getId().toString()));
        sendParsedVar(sender, "<gold>Owner: <white>{owner} <gray>({uuid})", Map.of("owner", t.getOwnerName() == null ? "unknown" : t.getOwnerName(), "uuid", t.getOwnerUuid() == null ? "null" : t.getOwnerUuid().toString()));

        World w = Bukkit.getWorld(t.getWorldName());
        if (w == null) {
            sendParsedVar(sender, cfgAdmin("status_world_unloaded", "<red>World not loaded: {world}"), Map.of("world", t.getWorldName()));
            return true;
        }
        int minY = Math.max(w.getMinHeight(), -63);
        int maxY = w.getMaxHeight();
        int topY = maxY - 1;
        long levelsPerChunk = Math.max(0, maxY - minY);
        long totalLevels = (long) t.getSize() * levelsPerChunk;
        long processedBefore = (long) t.getCurrentChunkIndex() * levelsPerChunk;
        long processedInCurrent = Math.max(0, (topY - t.getCurrentY()));
        long processedLevels = processedBefore + processedInCurrent;
        double progress = totalLevels == 0 ? 1.0 : Math.min(1.0, processedLevels / (double) totalLevels);

        String progressLine = cfgAdmin("status_progress_line", "<gold>Progress: <white>{percent}% ({processed}/{total} levels)")
                .replace("{percent}", String.format(Locale.ROOT, "%.2f", progress * 100.0))
                .replace("{processed}", String.valueOf(processedLevels))
                .replace("{total}", String.valueOf(totalLevels));
        sendParsed(sender, progressLine);

        long remainingSeconds = (processedLevels >= totalLevels) ? 0 : Math.max(1, (long) Math.ceil((1.0 - progress) * t.getDurationSeconds()));
        sendParsedVar(sender, cfgAdmin("status_eta_line", "<gold>ETA (est): <white>{seconds}s"), Map.of("seconds", String.valueOf(remainingSeconds)));

        sendParsedVar(sender, cfgAdmin("status_pointers", "<gold>Chunk index: <white>{idx} <gold>Y pointer: <white>{y}"), Map.of("idx", String.valueOf(t.getCurrentChunkIndex()), "y", String.valueOf(t.getCurrentY())));
        return true;
    }

    /* ---------------- Tab completion ---------------- */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = hasAdminPerm(sender);
        if (args.length == 1) {
            List<String> top = new ArrayList<>(List.of("give", "list", "reload", "admin"));
            if (!admin) top.remove("admin");
            String pref = args[0].toLowerCase(Locale.ROOT);
            return top.stream().filter(s -> s.startsWith(pref)).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            // suggest online player names for the 2nd arg (player)
            String pref = args[1].toLowerCase(Locale.ROOT);
            return Arrays.stream(Bukkit.getOnlinePlayers().toArray(new Player[0]))
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(pref))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            // suggest types for the 3rd arg (type)
            var sec = plugin.getConfig().getConfigurationSection("types");
            if (sec == null) return Collections.emptyList();
            String pref = args[2].toLowerCase(Locale.ROOT);
            return sec.getKeys(false).stream().filter(k -> k.startsWith(pref)).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && admin) {
            return Arrays.asList("save", "savetask", "list", "status").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("savetask") || args[1].equalsIgnoreCase("status")) && admin) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return manager.getActiveTasks().stream()
                    .map(t -> t.getId().toString())
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}