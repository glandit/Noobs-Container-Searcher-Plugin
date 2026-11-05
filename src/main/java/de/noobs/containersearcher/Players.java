package de.noobs.containersearcher;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;

import static de.noobs.containersearcher.Messages.nameOf;

/** Spieler per Name finden. */
public final class Players {

    private Players() {
    }

    /** Ohne den Server mit einer Mojang-Abfrage zu blockieren: online oder schon mal hier gewesen. */
    public static @Nullable OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    public static @Nullable UUID findByName(Collection<UUID> candidates, String name) {
        for (UUID uuid : candidates) {
            if (nameOf(uuid).equalsIgnoreCase(name) || uuid.toString().equalsIgnoreCase(name)) {
                return uuid;
            }
        }
        return null;
    }
}
