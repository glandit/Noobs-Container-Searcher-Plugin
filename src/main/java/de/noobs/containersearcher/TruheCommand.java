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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static de.noobs.containersearcher.Messages.nameOf;
import static de.noobs.containersearcher.Messages.placeholder;
import static java.util.Map.entry;

/**
 * /truhe info|vertrauen|misstrauen|freunde|reload
 * (englische Aliase: info, trust, untrust, friends)
 */
public final class TruheCommand implements TabExecutor {

    private enum Sub { INFO, TRUST, UNTRUST, FRIENDS, RELOAD, HELP }

    public static final String ADMIN_PERMISSION = "ncs.admin";

    private static final Map<String, Sub> ALIASES = Map.ofEntries(
            entry("info", Sub.INFO),
            entry("vertrauen", Sub.TRUST), entry("trust", Sub.TRUST),
            entry("misstrauen", Sub.UNTRUST), entry("untrust", Sub.UNTRUST),
            entry("freunde", Sub.FRIENDS), entry("friends", Sub.FRIENDS), entry("trustlist", Sub.FRIENDS),
            entry("reload", Sub.RELOAD),
            entry("hilfe", Sub.HELP), entry("help", Sub.HELP)
    );

    /** Was in der Tab-Vervollständigung angezeigt wird – deutsch oder englisch, je nach Spielsprache. */
    private static final List<String> SUGGESTED_DE = List.of("info", "vertrauen", "misstrauen", "freunde", "hilfe");
    private static final List<String> SUGGESTED_EN = List.of("info", "trust", "untrust", "friends", "help");

    private final NoobsContainerSearcher plugin;
    private final TrustStore trust;
    private final Messages messages;
    private final GroupService social;

    public TruheCommand(NoobsContainerSearcher plugin) {
        this.plugin = plugin;
        this.trust = plugin.trust();
        this.messages = plugin.messages();
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
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
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
            case INFO -> info(player);
            default -> { }
        }
        return true;
    }

    private boolean needsArg(Player player, String label, String[] args, String usageKey) {
        if (args.length >= 2) {
            return true;
        }
        messages.send(player, usageKey, placeholder("label", label));
        return false;
    }

    private void info(Player player) {
        Block block = plugin.targetBlock(player);
        Ownership ownership = plugin.ownership();
        if (block == null || !ownership.isTracked(block)) {
            messages.send(player, "no-target", placeholder("distance", plugin.settings().targetDistance()));
            return;
        }
        UUID owner = ownership.owner(block);
        if (owner == null) {
            messages.send(player, plugin.settings().claimOnFirstOpen() ? "info-none" : "info-none-no-claim");
            return;
        }
        // Gruppe und Freunde des Besitzers gehen nur ihn selbst (und Admins) etwas an
        if (!owner.equals(player.getUniqueId()) && !player.hasPermission(ADMIN_PERMISSION)) {
            boolean visible = plugin.modChannel().canSee(player.getUniqueId(), owner);
            messages.send(player, "info-other",
                    placeholder("owner", nameOf(owner)),
                    placeholder("visible", messages.raw(player, visible ? "word-yes" : "word-no")));
            return;
        }
        GroupStore.Group group = plugin.groups().groupOf(owner);
        ContainerSnapshot snapshot = plugin.index().get(ownership.canonical(block));
        messages.send(player, "info",
                placeholder("owner", nameOf(owner)),
                placeholder("group", group == null ? messages.raw(player, "nobody") : group.name()),
                placeholder("friends", messages.nameList(player, trust.friendsOf(owner))),
                placeholder("stacks", snapshot == null ? 0 : snapshot.items().size()));
    }

    // ------------------------------------------------------------------ Tab-Vervollständigung

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(messages.isGerman(sender) ? SUGGESTED_DE : SUGGESTED_EN);
            if (sender.hasPermission(ADMIN_PERMISSION)) {
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
            case TRUST -> filter(Bukkit.getOnlinePlayers().stream()
                    .filter(p -> !p.equals(player) && player.canSee(p))
                    .map(Player::getName).toList(), args[1]);
            case UNTRUST -> filter(trust.friendsOf(player.getUniqueId()).stream().map(Messages::nameOf).toList(), args[1]);
            default -> List.of();
        };
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
