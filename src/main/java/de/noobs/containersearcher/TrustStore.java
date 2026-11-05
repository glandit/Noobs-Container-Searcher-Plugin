package de.noobs.containersearcher;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * "Freunde": Spieler, denen ein Besitzer bei ALLEN seinen Truhen vertraut.
 * Gespeichert in plugins/Noobs-Container-Searcher/trust.yml.
 */
public final class TrustStore {

    private final File file;
    private final Logger logger;
    private final Map<UUID, Set<UUID>> trusted = new HashMap<>();

    public TrustStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load() {
        trusted.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            UUID owner = parse(key);
            if (owner == null) {
                continue;
            }
            Set<UUID> friends = new LinkedHashSet<>();
            for (String entry : yaml.getStringList(key)) {
                UUID friend = parse(entry);
                if (friend != null) {
                    friends.add(friend);
                }
            }
            if (!friends.isEmpty()) {
                trusted.put(owner, friends);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        trusted.forEach((owner, friends) ->
                yaml.set(owner.toString(), friends.stream().map(UUID::toString).toList()));
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Konnte trust.yml nicht speichern", e);
        }
    }

    public boolean trusts(UUID owner, UUID player) {
        Set<UUID> friends = trusted.get(owner);
        return friends != null && friends.contains(player);
    }

    public Set<UUID> friendsOf(UUID owner) {
        return Collections.unmodifiableSet(trusted.getOrDefault(owner, Set.of()));
    }

    /** @return false, wenn der Spieler schon Freund war */
    public boolean add(UUID owner, UUID player) {
        boolean added = trusted.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(player);
        if (added) {
            save();
        }
        return added;
    }

    /** @return false, wenn der Spieler kein Freund war */
    public boolean remove(UUID owner, UUID player) {
        Set<UUID> friends = trusted.get(owner);
        if (friends == null || !friends.remove(player)) {
            return false;
        }
        if (friends.isEmpty()) {
            trusted.remove(owner);
        }
        save();
        return true;
    }

    private static UUID parse(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
