package me.d15c07d.chunkcleaners.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class MessageUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private MessageUtil() { /* no instance */ }

    public static Component parse(String input) {
        if (input == null) return Component.empty();
        String s = input;

        if (s.indexOf('&') >= 0 || s.indexOf('§') >= 0) {
            try {
                return LEGACY.deserialize(s);
            } catch (Exception ex) {
                // fallback to MiniMessage if legacy parsing somehow fails
                try {
                    return MM.deserialize(s);
                } catch (Exception ignore) {
                    return Component.text(s);
                }
            }
        }

        try {
            return MM.deserialize(s);
        } catch (Exception ex) {
            try {
                return LEGACY.deserialize(s);
            } catch (Exception ignore) {
                return Component.text(s);
            }
        }
    }
}