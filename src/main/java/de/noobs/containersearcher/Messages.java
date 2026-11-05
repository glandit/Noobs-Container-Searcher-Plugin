package de.noobs.containersearcher;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Texte aus plugins/Noobs-Container-Searcher/lang/&lt;sprache&gt;.yml (MiniMessage).
 * Jeder Spieler bekommt seine Spielsprache, sonst Englisch – wie die Sprachdateien des Mods.
 */
public final class Messages {

    public static final String FALLBACK = "en_us";
    /** Steigt, wenn sich Texte so ändern, dass alte Dateien auf dem Server falsch wären. */
    private static final String FILE_VERSION = "file-version";
    /** Mitgelieferte Sprachen (dieselben wie im Mod "Noob's Container Searcher"). */
    public static final List<String> BUNDLED = List.of("en_us", "de_de", "pt_br", "ru_ru", "tr_tr");

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, YamlConfiguration> languages = new HashMap<>();
    private @Nullable String fixedLanguage;

    /** @param setting "auto" oder ein Sprachcode wie "de_de" */
    public void load(JavaPlugin plugin, String setting) {
        languages.clear();
        File folder = new File(plugin.getDataFolder(), "lang");
        for (String code : BUNDLED) {
            String resource = "lang/" + code + ".yml";
            File file = new File(folder, code + ".yml");
            if (!file.exists()) {
                plugin.saveResource(resource, false);
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            YamlConfiguration bundled = bundled(plugin, resource);
            // Texte mit geänderter Bedeutung (z.B. Sperren → Sync): alte Datei sichern, neue anlegen
            if (bundled != null && yaml.getInt(FILE_VERSION, 1) < bundled.getInt(FILE_VERSION, 1)) {
                File backup = new File(folder, code + ".yml.old");
                if (backup.delete() || !backup.exists()) {
                    if (file.renameTo(backup)) {
                        plugin.saveResource(resource, true);
                        yaml = YamlConfiguration.loadConfiguration(file);
                        plugin.getLogger().info("lang/" + code + ".yml updated, your old file is lang/" + backup.getName());
                    }
                }
            }
            // neue Texte aus Updates greifen auch, wenn die Datei auf dem Server älter ist
            if (bundled != null) {
                yaml.setDefaults(bundled);
            }
            languages.put(code, yaml);
        }
        // eigene Sprachen der Server-Admins, z.B. fr_fr.yml
        File[] extra = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (extra != null) {
            for (File file : extra) {
                String code = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
                languages.putIfAbsent(code, YamlConfiguration.loadConfiguration(file));
            }
        }
        String normalized = setting == null ? "auto" : setting.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        fixedLanguage = normalized.equals("auto") ? null : normalized;
        if (fixedLanguage != null && !languages.containsKey(fixedLanguage)) {
            plugin.getLogger().warning("Unknown language '" + setting + "', using " + FALLBACK);
            fixedLanguage = FALLBACK;
        }
    }

    private static @Nullable YamlConfiguration bundled(JavaPlugin plugin, String resource) {
        InputStream in = plugin.getResource(resource);
        if (in == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read " + resource, e);
            return null;
        }
    }

    // ------------------------------------------------------------------ Sprache wählen

    /** Sprachcode für diesen Empfänger: feste Einstellung, sonst Spielsprache des Spielers, sonst Englisch. */
    public String languageOf(@Nullable CommandSender target) {
        String code = fixedLanguage;
        if (code == null && target instanceof Player player) {
            code = player.locale().toString().toLowerCase(Locale.ROOT);
        }
        if (code == null || code.isEmpty()) {
            return FALLBACK;
        }
        if (languages.containsKey(code)) {
            return code;
        }
        // z.B. de_at → de_de, pt_pt → pt_br
        String prefix = code.split("_")[0] + "_";
        for (String known : BUNDLED) {
            if (known.startsWith(prefix)) {
                return known;
            }
        }
        for (String known : languages.keySet()) {
            if (known.startsWith(prefix)) {
                return known;
            }
        }
        return FALLBACK;
    }

    /** Spricht der Empfänger Deutsch? (für deutsche Befehlsvorschläge) */
    public boolean isGerman(CommandSender target) {
        return languageOf(target).startsWith("de_");
    }

    // ------------------------------------------------------------------ Senden

    public void send(CommandSender target, String key, TagResolver... placeholders) {
        List<String> lines = lines(target, key);
        if (lines != null) {
            for (String line : lines) {
                target.sendMessage(miniMessage.deserialize(line, placeholders));
            }
            return;
        }
        target.sendMessage(miniMessage.deserialize(raw(target, "prefix") + raw(target, key), placeholders));
    }

    public void actionBar(Player target, String key, TagResolver... placeholders) {
        target.sendActionBar(miniMessage.deserialize(raw(target, key), placeholders));
    }

    /** Nachricht als reiner Text ohne Präfix (für das Mod-UI). */
    public String plain(@Nullable CommandSender target, String key, TagResolver... placeholders) {
        List<String> lines = lines(target, key);
        String text = lines != null ? String.join(" ", lines) : raw(target, key);
        return PlainTextComponentSerializer.plainText().serialize(miniMessage.deserialize(text, placeholders));
    }

    public Component component(@Nullable CommandSender target, String key, TagResolver... placeholders) {
        return miniMessage.deserialize(raw(target, key), placeholders);
    }

    /** Roher MiniMessage-Text; fehlt der Schlüssel in der Sprache, kommt der englische. */
    public String raw(@Nullable CommandSender target, String key) {
        String value = language(target).getString(key);
        if (value == null) {
            value = languages.get(FALLBACK).getString(key);
        }
        return value == null ? "<red>Missing message: " + key : value;
    }

    private @Nullable List<String> lines(@Nullable CommandSender target, String key) {
        YamlConfiguration language = language(target);
        if (language.isList(key)) {
            return language.getStringList(key);
        }
        if (!language.contains(key)) {
            YamlConfiguration fallback = languages.get(FALLBACK);
            if (fallback.isList(key)) {
                return fallback.getStringList(key);
            }
        }
        return null;
    }

    private YamlConfiguration language(@Nullable CommandSender target) {
        return languages.getOrDefault(languageOf(target), languages.get(FALLBACK));
    }

    /** Für Tests: alle geladenen Sprachen. */
    Map<String, YamlConfiguration> languages() {
        return languages;
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    public static TagResolver placeholder(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    /** Name eines Spielers ohne Web-Abfrage. Unbekannt → kurze UUID. */
    public static String nameOf(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        String name = player.getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }

    public String nameList(CommandSender target, Collection<UUID> uuids) {
        if (uuids.isEmpty()) {
            return raw(target, "nobody");
        }
        List<String> names = uuids.stream().map(Messages::nameOf).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        return String.join(", ", names);
    }
}
