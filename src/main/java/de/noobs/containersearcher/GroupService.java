package de.noobs.containersearcher;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static de.noobs.containersearcher.Messages.nameOf;
import static de.noobs.containersearcher.Messages.placeholder;

/**
 * Gruppen- und Freunde-Aktionen – genutzt von /gruppe, /truhe vertrauen und vom Mod-UI.
 * Benachrichtigt andere Spieler im Chat und meldet Änderungen an {@link ClientSync}.
 */
public final class GroupService {

    private final NoobsContainerSearcher plugin;
    private final GroupStore groups;
    private final TrustStore trust;
    private final Messages messages;

    public GroupService(NoobsContainerSearcher plugin) {
        this.plugin = plugin;
        this.groups = plugin.groups();
        this.trust = plugin.trust();
        this.messages = plugin.messages();
    }

    // ------------------------------------------------------------------ Gruppe

    public Result create(Player player, String name) {
        GroupStore.Group current = groups.groupOf(player.getUniqueId());
        if (current != null) {
            return Result.fail("group-already-member", placeholder("group", current.name()));
        }
        if (!GroupStore.isValidName(name)) {
            return Result.fail("group-invalid-name");
        }
        if (groups.byName(name) != null) {
            return Result.fail("group-name-taken", placeholder("group", name));
        }
        GroupStore.Group group = groups.create(name, player.getUniqueId());
        plugin.sync().refresh(List.of(player.getUniqueId()));
        return Result.ok("group-created", placeholder("group", group.name()));
    }

    public Result invite(Player player, String targetName) {
        GroupStore.Group group = groups.groupOf(player.getUniqueId());
        Result notLeader = requireLeader(player, group);
        if (notLeader != null) {
            return notLeader;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !player.canSee(target)) {
            return Result.fail("group-player-offline", placeholder("player", targetName));
        }
        if (target.equals(player) || group.members().contains(target.getUniqueId())) {
            return Result.fail("group-already-in", placeholder("player", target.getName()));
        }
        if (groups.groupOf(target.getUniqueId()) != null) {
            return Result.fail("group-target-in-other", placeholder("player", target.getName()));
        }
        int seconds = plugin.settings().groupInviteSeconds();
        groups.invite(group, player.getUniqueId(), target.getUniqueId(), seconds * 1000L);
        messages.send(target, "group-invite-received",
                placeholder("player", player.getName()),
                placeholder("group", group.name()),
                placeholder("minutes", Math.max(1, seconds / 60)));
        plugin.sync().refresh(List.of(target.getUniqueId()));
        return Result.ok("group-invited", placeholder("player", target.getName()));
    }

    public Result accept(Player player) {
        GroupStore.Group current = groups.groupOf(player.getUniqueId());
        if (current != null) {
            return Result.fail("group-already-member", placeholder("group", current.name()));
        }
        GroupStore.Group group = groups.takeInvite(player.getUniqueId());
        if (group == null) {
            return Result.fail("group-no-invite");
        }
        notifyMembers(group, "group-member-joined", placeholder("player", player.getName()));
        groups.join(group, player.getUniqueId());
        plugin.sync().resync(group.members());
        return Result.ok("group-joined", placeholder("group", group.name()));
    }

    public Result decline(Player player) {
        GroupStore.Group group = groups.declineInvite(player.getUniqueId());
        if (group == null) {
            return Result.fail("group-no-invite");
        }
        Player leader = Bukkit.getPlayer(group.leader());
        if (leader != null) {
            messages.send(leader, "group-invite-declined", placeholder("player", player.getName()));
        }
        plugin.sync().refresh(List.of(player.getUniqueId()));
        return Result.ok("group-declined", placeholder("group", group.name()));
    }

    public Result leave(Player player) {
        GroupStore.Group current = groups.groupOf(player.getUniqueId());
        boolean wasLeader = current != null && current.isLeader(player.getUniqueId());
        GroupStore.Group group = groups.leave(player.getUniqueId());
        if (group == null) {
            return Result.fail("group-none");
        }
        notifyMembers(group, "group-member-left", placeholder("player", player.getName()));
        if (wasLeader && !group.members().isEmpty()) {
            Player newLeader = Bukkit.getPlayer(group.leader());
            if (newLeader != null) {
                messages.send(newLeader, "group-new-leader");
            }
        }
        List<UUID> affected = new ArrayList<>(group.members());
        affected.add(player.getUniqueId());
        plugin.sync().resync(affected);
        return Result.ok("group-left", placeholder("group", group.name()));
    }

    public Result kick(Player player, String targetName) {
        GroupStore.Group group = groups.groupOf(player.getUniqueId());
        Result notLeader = requireLeader(player, group);
        if (notLeader != null) {
            return notLeader;
        }
        UUID target = Players.findByName(group.members(), targetName);
        if (target == null) {
            return Result.fail("group-not-member", placeholder("player", targetName));
        }
        if (target.equals(player.getUniqueId())) {
            return Result.fail("group-kick-self");
        }
        String name = nameOf(target);
        groups.leave(target);
        Player kicked = Bukkit.getPlayer(target);
        if (kicked != null) {
            messages.send(kicked, "group-you-were-kicked", placeholder("group", group.name()));
        }
        notifyMembers(group, "group-member-kicked", placeholder("player", name), player.getUniqueId());
        List<UUID> affected = new ArrayList<>(group.members());
        affected.add(target);
        plugin.sync().resync(affected);
        return Result.ok("group-member-kicked", placeholder("player", name));
    }

    public Result disband(Player player) {
        GroupStore.Group group = groups.groupOf(player.getUniqueId());
        Result notLeader = requireLeader(player, group);
        if (notLeader != null) {
            return notLeader;
        }
        List<UUID> members = List.copyOf(group.members());
        notifyMembers(group, "group-disbanded", placeholder("group", group.name()), player.getUniqueId());
        groups.disband(group);
        plugin.sync().resync(members);
        return Result.ok("group-disbanded", placeholder("group", group.name()));
    }

    // ------------------------------------------------------------------ Freunde (sehen alle eigenen Kisten in der Suche)

    public Result trust(Player player, String targetName) {
        OfflinePlayer target = Players.resolve(targetName);
        if (target == null) {
            return Result.fail("unknown-player", placeholder("player", targetName));
        }
        String name = target.getName() != null ? target.getName() : targetName;
        if (target.getUniqueId().equals(player.getUniqueId())) {
            return Result.fail("trust-self");
        }
        if (!trust.add(player.getUniqueId(), target.getUniqueId())) {
            return Result.fail("already-trusted", placeholder("player", name));
        }
        Player online = target.getPlayer();
        if (online != null) {
            messages.send(online, "trusted-notify", placeholder("owner", player.getName()));
        }
        plugin.sync().resync(List.of(player.getUniqueId(), target.getUniqueId()));
        return Result.ok("trusted", placeholder("player", name));
    }

    public Result untrust(Player player, String targetName) {
        UUID target = Players.findByName(trust.friendsOf(player.getUniqueId()), targetName);
        if (target == null || !trust.remove(player.getUniqueId(), target)) {
            return Result.fail("not-trusted", placeholder("player", targetName));
        }
        plugin.sync().resync(List.of(player.getUniqueId(), target));
        return Result.ok("untrusted", placeholder("player", nameOf(target)));
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private @Nullable Result requireLeader(Player player, @Nullable GroupStore.Group group) {
        if (group == null) {
            return Result.fail("group-none");
        }
        if (!group.isLeader(player.getUniqueId())) {
            return Result.fail("group-not-leader", placeholder("leader", nameOf(group.leader())));
        }
        return null;
    }

    private void notifyMembers(GroupStore.Group group, String key, TagResolver placeholder) {
        notifyMembers(group, key, placeholder, null);
    }

    /** Alle Online-Mitglieder benachrichtigen, außer {@code except} (bekommt die Meldung als Ergebnis). */
    private void notifyMembers(GroupStore.Group group, String key, TagResolver placeholder, @Nullable UUID except) {
        for (UUID member : List.copyOf(group.members())) {
            if (member.equals(except)) {
                continue;
            }
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                messages.send(online, key, placeholder);
            }
        }
    }
}
