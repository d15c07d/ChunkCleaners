package me.d15c07d.chunkcleaners.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;


import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class HologramUtil {

    // Vertical spacing between hologram lines (blocks). Can be tuned if desired.
    private static final double LINE_SPACING = 0.25d;

    public static HologramHandle createHologram(Location base, String text) {
        if (text == null) text = "";
        String[] lines = text.split("\\r?\\n");
        List<String> l = new ArrayList<>();
        for (String s : lines) l.add(s);
        return createHologram(base, l);
    }

    public static HologramHandle createHologram(Location base, List<String> lines) {
        if (base == null) return new HologramHandle(base, new ArrayList<>());

        World w = base.getWorld();
        if (w == null) return new HologramHandle(base, new ArrayList<>());

        // Defensive copy of anchor to preserve exact coordinates
        Location anchor = base.clone();

        List<ArmorStand> stands = new ArrayList<>();

        // spawn top-to-bottom so lines list order is preserved visually
        // startY is the anchor Y; we compute each line relative to anchor so repeated updates use same anchor
        double startY = anchor.getY();
        int n = Math.max(1, lines == null ? 0 : lines.size());

        for (int i = 0; i < n; i++) {
            // top-to-bottom: compute y so index 0 is topmost line
            double y = startY + (n - 1 - i) * LINE_SPACING;
            Location spawnLoc = new Location(w, anchor.getX(), y, anchor.getZ());

            ArmorStand as;
            try {
                as = (ArmorStand) w.spawnEntity(spawnLoc, EntityType.ARMOR_STAND);
            } catch (Throwable t) {
                // If spawn fails, clean up and return empty handle
                for (ArmorStand spawned : stands) {
                    try { spawned.remove(); } catch (Throwable ignored) {}
                }
                return new HologramHandle(anchor, new ArrayList<>());
            }

            // Tweak stand appearance (best-effort; some methods may not exist across APIs)
            try { as.setGravity(false); } catch (Throwable ignored) {}
            try { as.setVisible(false); } catch (Throwable ignored) {}
            try { as.setInvulnerable(true); } catch (Throwable ignored) {}
            try { as.setCustomNameVisible(true); } catch (Throwable ignored) {}

            // Set initial line text if provided
            try {
                String raw = (lines != null && lines.size() > i) ? lines.get(i) : "";
                setName(as, MessageUtil.parse(raw));
            } catch (Throwable ignored) {}

            stands.add(as);
        }

        return new HologramHandle(anchor, stands);
    }

    private static void setName(ArmorStand stand, Component comp) {
        if (stand == null || stand.isDead()) return;
        try {
            Method m = stand.getClass().getMethod("setCustomName", Component.class);
            m.invoke(stand, comp);
            try {
                Method vis = stand.getClass().getMethod("setCustomNameVisible", boolean.class);
                vis.invoke(stand, true);
            } catch (Throwable ignore) { /* ignore */ }
            return;
        } catch (NoSuchMethodException nsme) {
            // fall through to legacy
        } catch (Throwable t) {
            // fall through to legacy
        }

        try {
            String legacy = LegacyComponentSerializer.legacySection().serialize(comp);
            stand.setCustomName(legacy);
            stand.setCustomNameVisible(true);
        } catch (Throwable t) {
            try {
                stand.setCustomName(comp.toString());
                stand.setCustomNameVisible(true);
            } catch (Throwable ignored) {}
        }
    }

    public static class HologramHandle {
        private final Location anchor; // preserved anchor location (immutable clone)
        private final List<ArmorStand> stands; // mutable list of current armor stands

        private HologramHandle(Location anchor, List<ArmorStand> stands) {
            this.anchor = anchor == null ? null : anchor.clone();
            this.stands = stands;
        }

        public void setLines(List<String> lines) {
            // Ensure main thread
            if (!Bukkit.isPrimaryThread()) {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("ChunkCleaners"), () -> setLines(lines));
                return;
            }

            // Remove existing stands
            for (ArmorStand as : new ArrayList<>(stands)) {
                try {
                    if (as != null && !as.isDead()) as.remove();
                } catch (Throwable ignored) {}
            }
            stands.clear();

            if (anchor == null || anchor.getWorld() == null) return;

            List<String> effective = lines == null ? List.of() : lines;
            int n = Math.max(1, effective.size());
            World w = anchor.getWorld();
            double startY = anchor.getY();

            for (int i = 0; i < n; i++) {
                double y = startY + (n - 1 - i) * LINE_SPACING;
                Location spawnLoc = new Location(w, anchor.getX(), y, anchor.getZ());
                ArmorStand as;
                try {
                    as = (ArmorStand) w.spawnEntity(spawnLoc, EntityType.ARMOR_STAND);
                } catch (Throwable t) {
                    // if spawn fails, stop trying and leave whatever we have
                    break;
                }
                try { as.setGravity(false); } catch (Throwable ignored) {}
                try { as.setVisible(false); } catch (Throwable ignored) {}
                try { as.setInvulnerable(true); } catch (Throwable ignored) {}
                try { as.setCustomNameVisible(true); } catch (Throwable ignored) {}
                try { setName(as, MessageUtil.parse(effective.get(i))); } catch (Throwable ignored) {}
                stands.add(as);
            }
        }

        public void setText(String text) {
            if (text == null) text = "";
            String[] arr = text.split("\\r?\\n");
            setLines(List.of(arr));
        }

        public void remove() {
            if (Bukkit.isPrimaryThread()) {
                for (ArmorStand as : new ArrayList<>(stands)) {
                    try {
                        if (as != null && !as.isDead()) as.remove();
                    } catch (Throwable ignored) {}
                }
                stands.clear();
            } else {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("ChunkCleaners"), this::remove);
            }
        }
    }
}