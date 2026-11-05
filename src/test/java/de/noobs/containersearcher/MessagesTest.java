package de.noobs.containersearcher;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static de.noobs.containersearcher.Messages.placeholder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    private static YamlConfiguration bundled(String code) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(MessagesTest.class.getResourceAsStream("/lang/" + code + ".yml")),
                StandardCharsets.UTF_8));
    }

    /** Jede Sprache hat alle Texte der englischen Datei – keine vergessenen Übersetzungen. */
    @Test
    void everyLanguageIsComplete() {
        Set<String> english = bundled("en_us").getKeys(false);
        List<String> problems = new ArrayList<>();
        for (String code : Messages.BUNDLED) {
            Set<String> keys = bundled(code).getKeys(false);
            for (String key : english) {
                if (!keys.contains(key)) {
                    problems.add(code + " fehlt: " + key);
                }
            }
            for (String key : keys) {
                if (!english.contains(key)) {
                    problems.add(code + " hat unbekannten Schlüssel: " + key);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** Alle Texte rendern ohne übrig gebliebene MiniMessage-Tags. */
    @Test
    void everyMessageRendersCleanly() {
        TagResolver all = TagResolver.resolver(List.of(
                placeholder("player", "Ben"), placeholder("owner", "Anna"), placeholder("group", "Noobs"),
                placeholder("label", "ncs"), placeholder("distance", 5),
                placeholder("shared", "Ben"), placeholder("friends", "Ben"), placeholder("list", "Ben"),
                placeholder("leader", "Anna"), placeholder("count", 2), placeholder("members", "Anna, Ben"),
                placeholder("minutes", 5), placeholder("stacks", 3), placeholder("visible", "yes")));
        List<String> problems = new ArrayList<>();
        for (String code : Messages.BUNDLED) {
            YamlConfiguration language = bundled(code);
            for (String key : language.getKeys(false)) {
                List<String> lines = language.isList(key) ? language.getStringList(key) : List.of(language.getString(key));
                for (String line : lines) {
                    String plain = PlainTextComponentSerializer.plainText()
                            .serialize(MiniMessage.miniMessage().deserialize(line, all));
                    if (plain.contains("<") || plain.contains(">")) {
                        problems.add(code + "/" + key + " → " + plain);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    // ------------------------------------------------------------------ Sprache pro Spieler

    private ServerMock server;
    private Messages messages;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        messages = MockBukkit.load(NoobsContainerSearcher.class).messages();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock player(String name, Locale locale) {
        PlayerMock player = server.addPlayer(name);
        player.setLocale(locale);
        // Befehle und Chat-Nachrichten gibt es nur mit dem Client-Mod
        ModChannel channel = ((NoobsContainerSearcher) server.getPluginManager().getPlugin("Noobs-Container-Searcher")).modChannel();
        channel.setTransport((p, bytes) -> { });
        channel.onPluginMessageReceived(ModChannel.CHANNEL, player, ModChannel.writeString("{\"t\":\"hello\",\"v\":2}"));
        return player;
    }

    @Test
    void eachPlayerGetsTheirGameLanguage() {
        assertEquals("Friends (see all your chests): Ben", messages.plain(player("Eng", Locale.US), "trust-list", placeholder("list", "Ben")));
        assertEquals("Freunde (sehen alle deine Truhen): Ben", messages.plain(player("Deu", Locale.GERMANY), "trust-list", placeholder("list", "Ben")));
        assertEquals("Amigos (veem todos os seus baús): Ben", messages.plain(player("Bra", Locale.of("pt", "BR")), "trust-list", placeholder("list", "Ben")));
        assertEquals("Друзья (видят все ваши сундуки): Ben", messages.plain(player("Rus", Locale.of("ru", "RU")), "trust-list", placeholder("list", "Ben")));
        assertEquals("Arkadaşlar (tüm sandıklarını görür): Ben", messages.plain(player("Tur", Locale.of("tr", "TR")), "trust-list", placeholder("list", "Ben")));
    }

    /** Sprachdateien vom ersten Test-Release (Sperren statt Sync) werden gesichert und ersetzt. */
    @Test
    void outdatedLanguageFilesAreReplaced() throws java.io.IOException {
        NoobsContainerSearcher plugin = (NoobsContainerSearcher) server.getPluginManager().getPlugin("Noobs-Container-Searcher");
        java.io.File file = new java.io.File(plugin.getDataFolder(), "lang/en_us.yml");
        java.nio.file.Files.writeString(file.toPath(), "trust-list: \"<gold>Friends (access to all chests): <white><list>\"\n");
        plugin.reload();
        assertEquals("Friends (see all your chests): Ben",
                plugin.messages().plain(player("Eng", Locale.US), "trust-list", placeholder("list", "Ben")));
        assertTrue(new java.io.File(plugin.getDataFolder(), "lang/en_us.yml.old").exists());
    }

    @Test
    void unknownLanguagesFallBackSensibly() {
        // Österreich → Deutsch, Portugal → brasilianisches Portugiesisch, Französisch → Englisch
        assertEquals("de_de", messages.languageOf(player("Aut", Locale.of("de", "AT"))));
        assertEquals("pt_br", messages.languageOf(player("Por", Locale.of("pt", "PT"))));
        assertEquals("en_us", messages.languageOf(player("Fra", Locale.FRANCE)));
        assertEquals("en_us", messages.languageOf(server.getConsoleSender()));
    }

    @Test
    void commandHintsUseThePlayersLanguage() {
        PlayerMock german = player("Deu", Locale.GERMANY);
        PlayerMock english = player("Eng", Locale.US);
        german.performCommand("gruppe einladen");
        english.performCommand("group invite");
        String de = PlainTextComponentSerializer.plainText().serialize(german.nextComponentMessage());
        String en = PlainTextComponentSerializer.plainText().serialize(english.nextComponentMessage());
        assertEquals("[Truhe] Benutzung: /gruppe einladen [Spieler]", de);
        assertEquals("[Chest] Usage: /group invite [player]", en);
    }
}
