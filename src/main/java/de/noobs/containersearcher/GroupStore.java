package de.noobs.containersearcher;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Gruppen: Alle Mitglieder haben Zugriff auf alle Truhen aller anderen Mitglieder.
 * Jeder Spieler kann in höchstens einer Gruppe sein. Beitritt nur per Einladung.
 * Gespeichert in plugins/Noobs-Container-Searcher/groups.yml.
 */
public final class GroupStore {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{3,16}");

    public static final class Group {
        private final String name;
        private UUID leader;
        private final Set<UUID> members = new LinkedHashSet<>();

        private Group(String name, UUID leader) {
            this.name = name;
            this.leader = leader;
            this.members.add(leader);
        }

        public String name() {
            return name;
        }

        public UUID leader() {
            return leader;
        }

        public boolean isLeader(UUID player) {
            return leader.equals(player);
        }

        public Set<UUID> members() {
            return Collections.unmodifiableSet(members);
        }

        private String key() {
            return GroupStore.key(name);
        }
    }

    /** Die Einladung hängt an genau dieser Gruppe – nicht am Namen, der nach dem Auflösen neu vergeben werden kann. */
    private record Invite(Group group, UUID inviter, long expiresAt) {
    }

    /** Offene Einladung: in welche Gruppe, von wem. */
    public record PendingInvite(Group group, UUID inviter) {
    }

    private final File file;
    private final Logger logger;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<UUID, Group> byMember = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();

    public GroupStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ Laden / Speichern

    public void load() {
        groups.clear();
        byMember.clear();
        invites.clear();
        if (!file.exists()) {
            return;
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("groups");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            UUID leader = parse(section.getString("leader", ""));
            String name = section.getString("name", key);
            if (leader == null || byMember.containsKey(leader) || groups.containsKey(key(name))) {
                logger.warning("Ungültige Gruppe in groups.yml übersprungen: " + key);
                continue;
            }
            Group group = new Group(name, leader);
            for (String raw : section.getStringList("members")) {
                UUID member = parse(raw);
                if (member != null && !byMember.containsKey(member)) {
                    group.members.add(member);
                }
            }
            groups.put(group.key(), group);
            group.members.forEach(member -> byMember.put(member, group));
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Group group : groups.values()) {
            String path = "groups." + group.key();
            yaml.set(path + ".name", group.name);
            yaml.set(path + ".leader", group.leader.toString());
            yaml.set(path + ".members", group.members.stream().map(UUID::toString).toList());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Konnte groups.yml nicht speichern", e);
        }
    }

    // ------------------------------------------------------------------ Abfragen

    public @Nullable Group groupOf(UUID player) {
        return byMember.get(player);
    }

    public @Nullable Group byName(String name) {
        return groups.get(key(name));
    }

    public Collection<Group> all() {
        return Collections.unmodifiableCollection(groups.values());
    }

    /** Sind beide Spieler in derselben Gruppe? */
    public boolean sameGroup(UUID a, UUID b) {
        Group group = byMember.get(a);
        return group != null && group.members.contains(b);
    }

    public static boolean isValidName(String name) {
        return NAME.matcher(name).matches();
    }

    // ------------------------------------------------------------------ Ändern

    /** Erstellt eine Gruppe. Aufrufer prüft vorher Name und ob der Spieler schon in einer Gruppe ist. */
    public Group create(String name, UUID leader) {
        Group group = new Group(name, leader);
        groups.put(group.key(), group);
        byMember.put(leader, group);
        invites.remove(leader);
        save();
        return group;
    }

    public void join(Group group, UUID player) {
        group.members.add(player);
        byMember.put(player, group);
        invites.remove(player);
        save();
    }

    /**
     * Spieler verlässt seine Gruppe (auch beim Rauswerfen).
     * Geht der Anführer, übernimmt das nächste Mitglied. Letztes Mitglied weg → Gruppe gelöscht.
     *
     * @return die verlassene Gruppe oder null, wenn der Spieler in keiner war
     */
    public @Nullable Group leave(UUID player) {
        Group group = byMember.remove(player);
        if (group == null) {
            return null;
        }
        group.members.remove(player);
        if (group.members.isEmpty()) {
            groups.remove(group.key());
            invites.values().removeIf(invite -> invite.group() == group);
        } else if (group.leader.equals(player)) {
            group.leader = group.members.iterator().next();
        }
        save();
        return group;
    }

    public void disband(Group group) {
        group.members.forEach(byMember::remove);
        group.members.clear();
        groups.remove(group.key());
        invites.values().removeIf(invite -> invite.group() == group);
        save();
    }

    // ------------------------------------------------------------------ Einladungen (nur im Speicher)

    public void invite(Group group, UUID inviter, UUID invitee, long timeoutMillis) {
        invites.put(invitee, new Invite(group, inviter, System.currentTimeMillis() + timeoutMillis));
    }

    /** Gilt die Einladung noch: nicht abgelaufen und genau diese Gruppe existiert weiterhin? */
    private boolean valid(Invite invite) {
        return invite.expiresAt() >= System.currentTimeMillis()
                && groups.get(invite.group().key()) == invite.group();
    }

    /** Offene, gültige Einladung ansehen, ohne sie einzulösen. */
    public @Nullable PendingInvite pendingInvite(UUID invitee) {
        Invite invite = invites.get(invitee);
        if (invite == null) {
            return null;
        }
        if (!valid(invite)) {
            invites.remove(invitee);
            return null;
        }
        return new PendingInvite(invite.group(), invite.inviter());
    }

    /** Gültige Einladung einlösen (wird dabei entfernt). */
    public @Nullable Group takeInvite(UUID invitee) {
        Invite invite = invites.remove(invitee);
        return invite != null && valid(invite) ? invite.group() : null;
    }

    /** @return Gruppe der abgelehnten Einladung oder null */
    public @Nullable Group declineInvite(UUID invitee) {
        Invite invite = invites.remove(invitee);
        return invite != null && valid(invite) ? invite.group() : null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static @Nullable UUID parse(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
