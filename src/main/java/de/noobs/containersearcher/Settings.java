package de.noobs.containersearcher;

import org.bukkit.configuration.file.FileConfiguration;

/** Einstellungen aus config.yml, beim Laden einmal ausgelesen. */
public record Settings(
        boolean claimOnFirstOpen,
        int targetDistance,
        int groupInviteSeconds
) {

    public static Settings from(FileConfiguration config) {
        return new Settings(
                config.getBoolean("claim-on-first-open", true),
                Math.max(1, Math.min(10, config.getInt("target-distance", 5))),
                Math.max(10, config.getInt("groups.invite-seconds", 300))
        );
    }
}
