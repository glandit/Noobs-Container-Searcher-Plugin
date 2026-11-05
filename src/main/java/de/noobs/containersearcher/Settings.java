package de.noobs.containersearcher;

import org.bukkit.configuration.file.FileConfiguration;

/** Einstellungen aus config.yml, beim Laden einmal ausgelesen. */
public record Settings(
        boolean autoLock,
        boolean protectFromExplosions,
        boolean protectFromHoppers,
        boolean notifyAdminAccess,
        int targetDistance,
        boolean groupMembersCanBreak,
        int groupInviteSeconds
) {

    public static Settings from(FileConfiguration config) {
        return new Settings(
                config.getBoolean("auto-lock", true),
                config.getBoolean("protect-from-explosions", true),
                config.getBoolean("protect-from-hoppers", true),
                config.getBoolean("notify-admin-access", true),
                Math.max(1, Math.min(10, config.getInt("target-distance", 5))),
                config.getBoolean("groups.members-can-break", true),
                Math.max(10, config.getInt("groups.invite-seconds", 300))
        );
    }
}
