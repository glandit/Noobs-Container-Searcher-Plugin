package de.noobs.containersearcher;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static de.noobs.containersearcher.Messages.nameOf;
import static de.noobs.containersearcher.Messages.placeholder;
import static java.util.Map.entry;

/**
 * /truhe teilen|entziehen|info|sperren|entsperren|vertrauen|misstrauen|freunde|reload
 * (englische Aliase: share, unshare, info, lock, unlock, trust, untrust, trustlist)
 */
public final class TruheCommand implements TabExecutor {

    private enum Sub { SHARE, UNSHARE, INFO, LOCK, UNLOCK, TRUST, UNTRUST, FRIENDS, RELOAD, HELP }

    private static final Map<String, Sub> ALIASES = Map.ofEntries(
            entry("teilen", Sub.SHARE), entry("share", Sub.SHARE), entry("add", Sub.SHARE),
            entry("entziehen", Sub.UNSHARE), entry("unshare", Sub.UNSHARE), entry("remove", Sub.UNSHARE),
            entry("info", Sub.INFO),
            entry("sperren", Sub.LOCK), entry("lock", Sub.LOCK),
            entry("entsperren", Sub.UNLOCK), entry("unlock", Sub.UNLOCK),
            entry("vertrauen", Sub.TRUST), entry("trust", Sub.TRUST),
            entry("misstrauen", Sub.UNTRUST), entry("untrust", Sub.UNTRUST),
            entry("freunde", Sub.FRIENDS), entry("friends", Sub.FRIENDS), entry("trustlist", Sub.FRIENDS),
            entry("reload", Sub.RELOAD),
            entry("hilfe", Sub.HELP), entry("help", Sub.HELP)
    );

    /** Was in der Tab-Vervollständigung angezeigt wird – deutsch oder englisch, je nach Spielsprache. */
    private static final List<String> SUGGESTED_DE = List.of(
            "teilen", "entziehen", "info", "sperren", "entsperren", "vertrauen", "misstrauen", "freunde", "hilfe");
    private static final List<String> SUGGESTED_EN = List.of(
            "share", "unshare", "info", "lock", "unlock", "trust", "untrust", "friends", "help");

    private final NoobsContainerSearcher plugin;
    private final LockManager locks;
    private final TrustStore trust;
    private final Messages messages;
    private final ChestService chests;
    private final GroupService social;

    public TruheCommand(NoobsContainerSearcher plugin) {
        this.plugin = plugin;
        this.locks = plugin.locks();
        this.trust = plugin.trust();
        this.messages = plugin.messages();
        this.chests = plugin.chestService();
        this.social = plugin.groupService();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Sub sub = args.length == 0 ? Sub.HELP : ALIASES.getOrDefault(args[0].toLowerCase(Locale.ROOT), Sub.HELP);

        if (sub == Sub.HELP) {
            messages.send(sender, "help", placeholder("label", label));
            return true;
        }
        if (sub == Sub.RELOAD) {
            if (!sender.hasPermission(LockManager.ADMIN_PERMISSION)) {
                messages.send(sender, "no-permission");
                return true;
            }
            plugin.reload();
            messages.send(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "no-player");
            return true;
        }

        switch (sub) {
            case TRUST -> {
                if (needsArg(player, label, args, "usage-trust")) {
                    social.trust(player, args[1]).send(messages, player);
                }
            }
            case UNTRUST -> {
                if (needsArg(player, label, args, "usage-untrust")) {
                    social.untrust(player, args[1]).send(messages, player);
                }
            }
            case FRIENDS -> messages.send(player, "trust-list",
                    placeholder("list", messages.nameList(player, trust.friendsOf(player.getUniqueId()))));
            default -> blockCommand(player, sub, label, args);
        }
        return true;
    }

    // ------------------------------------------------------------------ Befehle mit Truhe

    private void blockCommand(Player player, Sub sub, String label, String[] args) {
        int distance = plugin.settings().targetDistance();
        Block block = chests.targetBlock(player);
        if (block == null || !locks.isLockable(block)) {
            messages.send(player, "no-target", placeholder("distance", distance));
            return;
        }

        switch (sub) {
            case INFO -> info(player, block);
            case LOCK -> chests.lock(player, block).send(messages, player);
            case UNLOCK -> chests.unlock(player, block).send(messages, player);
            case SHARE -> {
                if (needsArg(player, label, args, "usage-share")) {
                    chests.share(player, block, args[1]).send(messages, player);
                }
            }
            case UNSHARE -> {
                if (needsArg(player, label, args, "usage-unshare")) {
                    chests.unshare(player, block, args[1]).send(messages, player);
                }
            }
            default -> { }
        }
    }

    private boolean needsArg(Player player, String label, String[] args, String usageKey) {
        if (args.length >= 2) {
            return true;
        }
        messages.send(player, usageKey, placeholder("label", label));
        return false;
    }

    private void info(Player player, Block block) {
        ContainerLock lock = locks.getLock(block);
        if (lock == null) {
            messages.send(player, "info-unlocked");
            return;
        }
        // Fremde sehen nur, wem die Truhe gehört – nicht, mit wem sie geteilt ist
        if (!locks.canOpen(player, block)) {
            messages.send(player, "denied", placeholder("owner", nameOf(lock.owner())));
            return;
        }
        GroupStore.Group group = plugin.groups().groupOf(lock.owner());
        // die Freundesliste des Besitzers geht nur ihn selbst (und Admins) etwas an
        messages.send(player, locks.canManage(player, lock) ? "info" : "info-member",
                placeholder("owner", nameOf(lock.owner())),
                placeholder("shared", messages.nameList(player, lock.shared())),
                placeholder("friends", messages.nameList(player, trust.friendsOf(lock.owner()))),
                placeholder("group", group == null ? messages.raw(player, "nobody") : group.name()));
    }

    // ------------------------------------------------------------------ Tab-Vervollständigung

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(messages.isGerman(sender) ? SUGGESTED_DE : SUGGESTED_EN);
            if (sender.hasPermission(LockManager.ADMIN_PERMISSION)) {
                options.add("reload");
            }
            return filter(options, args[0]);
        }
        if (args.length != 2 || !(sender instanceof Player player)) {
            return List.of();
        }
        Sub sub = ALIASES.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            return List.of();
        }
        return switch (sub) {
            case SHARE, TRUST -> filter(Bukkit.getOnlinePlayers().stream()
                    .filter(p -> !p.equals(player) && player.canSee(p))
                    .map(Player::getName).toList(), args[1]);
            case UNSHARE -> {
                Block block = chests.targetBlock(player);
                ContainerLock lock = block == null || !locks.isLockable(block) ? null : locks.getLock(block);
                // nur wer die Truhe verwalten darf, bekommt die Freigaben vorgeschlagen
                yield lock == null || !locks.canManage(player, lock) ? List.of() : filter(names(lock.shared()), args[1]);
            }
            case UNTRUST -> filter(names(trust.friendsOf(player.getUniqueId())), args[1]);
            default -> List.of();
        };
    }

    private static List<String> names(Collection<UUID> uuids) {
        return uuids.stream().map(Messages::nameOf).toList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
