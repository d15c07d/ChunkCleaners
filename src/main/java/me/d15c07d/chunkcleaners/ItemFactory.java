package me.d15c07d.chunkcleaners;

import me.d15c07d.chunkcleaners.config.ConfigManager;
import me.d15c07d.chunkcleaners.utils.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class ItemFactory {

    // Stable PDC key used for cleaner type. Must match the key used by PlacementListener.
    private static final String PDC_KEY = "chunkcleaner-type";

    private ItemFactory() { /* no instances */ }

    public static ItemStack createCleanerItem(ChunkCleanersPlugin plugin, ConfigManager.CleanerType type, int amount) {
        Material mat = Material.getMaterial(type.getBlockMaterial());
        if (mat == null) mat = Material.END_PORTAL_FRAME;
        ItemStack item = new ItemStack(mat, Math.max(1, amount));

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Parse the display name and description as Components
        String rawDisplay = type.getDisplayName();
        Component displayComp = MessageUtil.parse(rawDisplay);

        String rawDescription = type.getDescription() == null ? "" : type.getDescription();
        List<Component> loreComponents = Arrays.stream(rawDescription.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(MessageUtil::parse)
                .collect(Collectors.toList());

        // Set display name using Component API if available
        try {
            Method displayMethod = meta.getClass().getMethod("setDisplayName", Component.class);
            displayMethod.invoke(meta, displayComp);
        } catch (NoSuchMethodException nsme) {
            // Fallback: serialize to legacy section string and use setDisplayName(String)
            String legacy = LegacyComponentSerializer.legacySection().serialize(displayComp);
            meta.setDisplayName(legacy);
        } catch (ReflectiveOperationException roe) {
            // Any other reflection error -> fallback to legacy string
            String legacy = LegacyComponentSerializer.legacySection().serialize(displayComp);
            meta.setDisplayName(legacy);
        }

        // Set lore: try to call setLore(List<Component>) (if supported), otherwise fallback to List<String>
        try {
            Method setLoreMethod = meta.getClass().getMethod("setLore", List.class);
            // Try with Component list first
            try {
                setLoreMethod.invoke(meta, loreComponents);
            } catch (IllegalArgumentException iae) {
                // API expects List<String> instead; fallback to outer handler
                throw iae;
            }
        } catch (ReflectiveOperationException | IllegalArgumentException ex) {
            // Fallback serialization to legacy section strings and set via meta.setLore(List<String>)
            List<String> legacyLore = loreComponents.stream()
                    .map(c -> LegacyComponentSerializer.legacySection().serialize(c))
                    .collect(Collectors.toList());
            meta.setLore(legacyLore);
        }

        // Persist the cleaner type key so placement listener can read it
        // Use the stable PDC key that PlacementListener expects ("chunkcleaner-type")
        NamespacedKey key = new NamespacedKey(plugin, PDC_KEY);
        try {
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, type.getKey());
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to write chunk-cleaner PDC key: " + t.getMessage());
        }

        item.setItemMeta(meta);
        return item;
    }
}