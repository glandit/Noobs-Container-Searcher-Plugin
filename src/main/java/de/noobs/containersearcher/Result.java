package de.noobs.containersearcher;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** Ergebnis einer Aktion: Nachrichten-Schlüssel + Platzhalter. Befehle schreiben es in den Chat, der Mod zeigt es im UI. */
public record Result(boolean ok, String key, TagResolver... placeholders) {

    public static Result ok(String key, TagResolver... placeholders) {
        return new Result(true, key, placeholders);
    }

    public static Result fail(String key, TagResolver... placeholders) {
        return new Result(false, key, placeholders);
    }

    public void send(Messages messages, CommandSender target) {
        messages.send(target, key, placeholders);
    }
}
