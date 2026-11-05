package de.noobs.containersearcher;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static de.noobs.containersearcher.Messages.nameOf;
import static de.noobs.containersearcher.Messages.placeholder;
import static java.util.Map.entry;

/**
 * /gruppe erstellen|einladen|annehmen|ablehnen|verlassen|entfernen|auflösen|info
 * Alle Mitglieder einer Gruppe haben Zugriff auf alle Truhen der anderen Mitglieder.
 */
public final class GruppeCommand implements TabExecutor {

    private enum Sub { CREATE, INVITE, ACCEPT, DECLINE, LEAVE, KICK, DISBAND, INFO, HELP }

    private static final Map<String, Sub> ALIASES = Map.ofEntries(
            entry("erstellen", Sub.CREATE), entry("create", Sub.CREATE),
            entry("einladen", Sub.INVITE), entry("invite", Sub.INVITE),
            entry("annehmen", Sub.ACCEPT), entry("accept", Sub.ACCEPT), entry("beitreten", Sub.ACCEPT),
            entry("ablehnen", Sub.DECLINE), entry("decline", Sub.DECLINE),
            entry("verlassen", Sub.LEAVE), entry("leave", Sub.LEAVE),
            entry("entfernen", Sub.KICK), entry("rauswerfen", Sub.KICK), entry("kick", Sub.KICK),
            entry("auflösen", Sub.DISBAND), entry("aufloesen", Sub.DISBAND), entry("disband", Sub.DISBAND),
            entry("info", Sub.INFO),
            entry("hilfe", Sub.HELP), entry("help", Sub.HELP)
    );

    private static final List<String> SUGGESTED_DE = List.of(
            "erstellen", "einladen", "annehmen", "ablehnen", "verlassen", "entfernen", "auflösen", "info", "hilfe");
    private static final List<String> SUGGESTED_EN = List.of(
            "create", "invite", "accept", "decline", "leave", "kick", "disband", "info", "help");

    private final GroupStore groups;
    private final GroupService service;
    private final Messages messages;

    public GruppeCommand(NoobsContainerSearcher plugin) {
        this.groups = plugin.groups();
        this.service = plugin.groupService();
        this.messages = plugin.messages();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "no-player");
            return true;
        }
        Sub sub;
        if (args.length == 0) {
            sub = groups.groupOf(player.getUniqueId()) != null ? Sub.INFO : Sub.HELP;
        } else {
            sub = ALIASES.getOrDefault(args[0].toLowerCase(Locale.ROOT), Sub.HELP);
        }

        switch (sub) {
            case CREATE -> withArg(player, label, args, "usage-group-create", name -> service.create(player, name));
            case INVITE -> withArg(player, label, args, "usage-group-invite", name -> service.invite(player, name));
            case ACCEPT -> service.accept(player).send(messages, player);
            case DECLINE -> service.decline(player).send(messages, player);
            case LEAVE -> service.leave(player).send(messages, player);
            case KICK -> withArg(player, label, args, "usage-group-kick", name -> service.kick(player, name));
            case DISBAND -> service.disband(player).send(messages, player);
            case INFO -> info(player);
            case HELP -> messages.send(player, "group-help", placeholder("label", label));
        }
        return true;
    }

    private void withArg(Player player, String label, String[] args, String usageKey,
                         java.util.function.Function<String, Result> action) {
        if (args.length < 2) {
            messages.send(player, usageKey, placeholder("label", label));
            return;
        }
        action.apply(args[1]).send(messages, player);
    }

    private void info(Player player) {
        GroupStore.Group group = groups.groupOf(player.getUniqueId());
        if (group == null) {
            messages.send(player, "group-none");
            return;
        }
        List<Component> names = group.members().stream()
                .<Component>map(member -> Component.text(nameOf(member),
                        Bukkit.getPlayer(member) != null ? NamedTextColor.GREEN : NamedTextColor.GRAY))
                .toList();
        messages.send(player, "group-info",
                placeholder("group", group.name()),
                placeholder("leader", nameOf(group.leader())),
                placeholder("count", group.members().size()),
                Placeholder.component("members", Component.join(JoinConfiguration.commas(true), names)));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(messages.isGerman(player) ? SUGGESTED_DE : SUGGESTED_EN, args[0]);
        }
        if (args.length != 2) {
            return List.of();
        }
        Sub sub = ALIASES.get(args[0].toLowerCase(Locale.ROOT));
        GroupStore.Group group = groups.groupOf(player.getUniqueId());
        if (sub == Sub.INVITE) {
            return filter(Bukkit.getOnlinePlayers().stream()
                    .filter(p -> player.canSee(p) && groups.groupOf(p.getUniqueId()) == null)
                    .map(Player::getName).toList(), args[1]);
        }
        if (sub == Sub.KICK && group != null) {
            return filter(group.members().stream()
                    .filter(member -> !member.equals(player.getUniqueId()))
                    .map(Messages::nameOf).toList(), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
