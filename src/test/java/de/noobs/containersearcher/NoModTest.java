package de.noobs.containersearcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spieler ohne den Client-Mod: keine Befehle, keine Chat-Nachrichten, keine Einladungen. */
class NoModTest {

    private ServerMock server;
    private NoobsContainerSearcher plugin;
    private ModChannel channel;
    private PlayerMock anna;
    private PlayerMock ben;
    private final Map<Player, List<JsonObject>> inbox = new HashMap<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(NoobsContainerSearcher.class);
        channel = plugin.modChannel();
        channel.setTransport((player, bytes) -> inbox.computeIfAbsent(player, k -> new ArrayList<>())
                .add(JsonParser.parseString(ModChannel.readString(bytes)).getAsJsonObject()));
        anna = server.addPlayer("Anna");
        ben = server.addPlayer("Ben"); // Ben hat den Mod nicht
        hello(anna);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void hello(Player player) {
        channel.onPluginMessageReceived(ModChannel.CHANNEL, player, ModChannel.writeString("{\"t\":\"hello\",\"v\":2}"));
    }

    private static List<String> chat(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        Component line;
        while ((line = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return lines;
    }

    private JsonObject lastState(Player player) {
        List<JsonObject> messages = inbox.getOrDefault(player, List.of());
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).get("t").getAsString().equals("state")) {
                return messages.get(i);
            }
        }
        return null;
    }

    @Test
    void commandsOnlyAnswerWithAHint() {
        ben.performCommand("gruppe erstellen Noobs");
        ben.performCommand("truhe vertrauen Anna");
        ben.performCommand("ncs");
        assertNull(plugin.groups().groupOf(ben.getUniqueId()));
        assertFalse(plugin.trust().trusts(ben.getUniqueId(), anna.getUniqueId()));

        List<String> lines = chat(ben);
        assertEquals(3, lines.size(), lines.toString());
        for (String line : lines) {
            assertTrue(line.contains("Noob's Container Searcher"), line);
        }
        assertTrue(plugin.getCommand("gruppe").tabComplete(ben, "gruppe", new String[]{""}).isEmpty());
        assertTrue(plugin.getCommand("truhe").tabComplete(ben, "truhe", new String[]{""}).isEmpty());
    }

    @Test
    void commandsWorkOnceTheModSaysHello() {
        hello(ben);
        ben.performCommand("gruppe erstellen Noobs");
        assertEquals("Noobs", plugin.groups().groupOf(ben.getUniqueId()).name());
    }

    @Test
    void adminWithoutModCanStillReload() {
        ben.setOp(true);
        ben.performCommand("ncs reload");
        assertEquals(List.of("[Chest] Configuration reloaded."), chat(ben));
    }

    @Test
    void commandsAreHiddenUntilTheModSaysHello() {
        List<String> sent = new ArrayList<>(List.of("help", "truhe", "ncs", "kiste", "gruppe", "group", "ncsgroup",
                "noobs-container-searcher:truhe", "noobs-container-searcher:gruppe"));
        PlayerCommandSendEvent event = new PlayerCommandSendEvent(ben, sent);
        server.getPluginManager().callEvent(event);
        assertEquals(List.of("help"), List.copyOf(event.getCommands()));

        List<String> forAnna = new ArrayList<>(List.of("help", "truhe", "gruppe"));
        server.getPluginManager().callEvent(new PlayerCommandSendEvent(anna, forAnna));
        assertEquals(List.of("help", "truhe", "gruppe"), forAnna);
    }

    @Test
    void noChatMessagesWithoutTheMod() {
        chat(anna);
        anna.performCommand("truhe vertrauen Ben");
        assertTrue(plugin.trust().trusts(anna.getUniqueId(), ben.getUniqueId()), "Vertrauen geht – Ben sieht es mit Mod");
        assertTrue(chat(ben).isEmpty(), "keine \"hat dich als Freund eingetragen\"-Nachricht");

        // alte Truhe: Ben wird still Besitzer, ohne Chat-Nachricht
        WorldMock world = server.addSimpleWorld("world");
        world.loadChunk(0, 0);
        ben.teleport(new Location(world, 0.5, 64, 2.5));
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.CHEST);
        ben.openInventory(((Container) block.getState(false)).getInventory());
        assertEquals(ben.getUniqueId(), plugin.ownership().owner(block));
        assertTrue(chat(ben).isEmpty(), "keine \"gehört jetzt dir\"-Nachricht");
    }

    @Test
    void playersWithoutModCannotBeInvited() {
        anna.performCommand("gruppe erstellen Noobs");
        chat(anna);
        anna.performCommand("gruppe einladen Ben");
        assertNull(plugin.groups().pendingInvite(ben.getUniqueId()));
        assertTrue(chat(ben).isEmpty());
        assertEquals(List.of("[Chest] Ben does not have the mod Noob's Container Searcher and cannot join groups."), chat(anna));
    }

    /** Nach /reload schickt der Mod kein neues Hallo – wer den Kanal angemeldet hat, bleibt Mod-Spieler. */
    @Test
    @SuppressWarnings("unchecked")
    void modPlayersStayModPlayersAfterReload() throws ReflectiveOperationException {
        // wie Fabric per minecraft:register – PlayerMock gibt nur eine Kopie heraus
        java.lang.reflect.Field channels = PlayerMock.class.getDeclaredField("channels");
        channels.setAccessible(true);
        ((java.util.Set<String>) channels.get(anna)).add(ModChannel.CHANNEL);
        server.getPluginManager().disablePlugin(plugin);
        server.getPluginManager().enablePlugin(plugin);
        assertTrue(plugin.modChannel().isModClient(anna));
        assertFalse(plugin.modChannel().isModClient(ben));
        anna.performCommand("gruppe erstellen Noobs");
        assertEquals("Noobs", plugin.groups().groupOf(anna.getUniqueId()).name());
    }

    @Test
    void onlineListShowsOnlyPlayersWithTheMod() {
        PlayerMock carl = server.addPlayer("Carl");
        hello(carl);
        String online = lastState(anna).getAsJsonArray("online").toString();
        assertTrue(online.contains("Carl"), online);
        assertFalse(online.contains("Ben"), online);
    }
}
