package de.noobs.containersearcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Simuliert den Client-Mod: schickt JSON über den Kanal und prüft die Antworten. */
class ModChannelTest {

    private ServerMock server;
    private NoobsContainerSearcher plugin;
    private ModChannel channel;
    private WorldMock world;
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
        world = server.addSimpleWorld("world");
        anna = server.addPlayer("Anna");
        ben = server.addPlayer("Ben");
        anna.teleport(new Location(world, 0.5, 64, 2.5));
        ben.teleport(new Location(world, 40.5, 64, 40.5));
        world.loadChunk(0, 0); // auf dem echten Server: Spieler in der Nähe
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void send(Player player, String json) {
        channel.onPluginMessageReceived(ModChannel.CHANNEL, player, ModChannel.writeString(json));
    }

    /** Letzte Nachricht eines Typs an den Spieler. */
    private JsonObject last(Player player, String type) {
        List<JsonObject> messages = inbox.getOrDefault(player, List.of());
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).get("t").getAsString().equals(type)) {
                return messages.get(i);
            }
        }
        return null;
    }

    private Block chest() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.CHEST);
        return block;
    }

    @Test
    void stringCodecRoundTrip() {
        String longText = "ä".repeat(300) + "x".repeat(20000);
        assertEquals(longText, ModChannel.readString(ModChannel.writeString(longText)));
        assertEquals("", ModChannel.readString(ModChannel.writeString("")));
    }

    @Test
    void helloAnswersWithHelloAndState() {
        send(anna, "{\"t\":\"hello\",\"v\":1}");
        assertNotNull(last(anna, "hello"));
        JsonObject state = last(anna, "state");
        assertNotNull(state);
        assertTrue(state.get("group").isJsonNull());
        assertTrue(state.getAsJsonArray("online").toString().contains("Ben"));
    }

    @Test
    void groupFlowThroughUi() {
        send(anna, "{\"t\":\"hello\"}");
        send(ben, "{\"t\":\"hello\"}");

        send(anna, "{\"t\":\"group\",\"op\":\"create\",\"arg\":\"Noobs\"}");
        assertTrue(last(anna, "result").get("ok").getAsBoolean());
        assertEquals("Noobs", last(anna, "state").getAsJsonObject("group").get("name").getAsString());

        send(anna, "{\"t\":\"group\",\"op\":\"invite\",\"arg\":\"Ben\"}");
        JsonObject invite = last(ben, "state").getAsJsonObject("invite");
        assertEquals("Anna", invite.get("from").getAsString());

        send(ben, "{\"t\":\"group\",\"op\":\"accept\"}");
        assertTrue(plugin.groups().sameGroup(anna.getUniqueId(), ben.getUniqueId()));
        assertEquals(2, last(anna, "state").getAsJsonObject("group").getAsJsonArray("members").size());
        assertTrue(last(ben, "state").get("invite").isJsonNull());
    }

    @Test
    void shareChestByPosition() {
        Block block = chest();
        plugin.locks().setLock(block, ContainerLock.ownedBy(anna.getUniqueId()));

        // Ben sieht nur den Besitzer, nicht mit wem geteilt ist
        send(ben, "{\"t\":\"info\",\"pos\":[0,64,0]}");
        JsonObject forBen = last(ben, "chest");
        assertEquals("locked", forBen.get("status").getAsString());
        assertEquals("Anna", forBen.get("owner").getAsString());
        assertFalse(forBen.get("access").getAsBoolean());
        assertNull(forBen.get("shared"));

        send(anna, "{\"t\":\"share\",\"pos\":[0,64,0],\"player\":\"Ben\"}");
        assertTrue(last(anna, "result").get("ok").getAsBoolean());
        assertTrue(last(anna, "chest").getAsJsonArray("shared").toString().contains("Ben"));

        send(ben, "{\"t\":\"info\",\"pos\":[0,64,0]}");
        assertTrue(last(ben, "chest").get("access").getAsBoolean());
        assertEquals("shared", last(ben, "chest").get("reason").getAsString());
    }

    @Test
    void strangerCannotManageAndFarClaimIsRefused() {
        Block block = chest();
        plugin.locks().setLock(block, ContainerLock.ownedBy(anna.getUniqueId()));

        send(ben, "{\"t\":\"unlock\",\"pos\":[0,64,0]}");
        assertFalse(last(ben, "result").get("ok").getAsBoolean());
        assertNotNull(plugin.locks().getLock(block));

        // Ungesicherte Truhe aus 40 Blöcken Entfernung sichern → abgelehnt
        plugin.locks().clearLock(block);
        send(ben, "{\"t\":\"lock\",\"pos\":[0,64,0]}");
        assertFalse(last(ben, "result").get("ok").getAsBoolean());
        assertNull(plugin.locks().getLock(block));

        // Anna steht daneben → geht
        send(anna, "{\"t\":\"lock\",\"pos\":[0,64,0]}");
        assertTrue(last(anna, "result").get("ok").getAsBoolean());
        assertNotNull(plugin.locks().getLock(block));
    }

    @Test
    void badInputIsIgnored() {
        channel.onPluginMessageReceived(ModChannel.CHANNEL, anna, new byte[]{(byte) 0xFF, (byte) 0xFF});
        send(anna, "kein json");
        send(anna, "{\"t\":\"info\",\"pos\":[1,2]}");
        send(anna, "{\"t\":\"info\",\"pos\":[0,99999,0]}");
        assertEquals("unloaded", last(anna, "chest").get("status").getAsString());

        world.getBlockAt(5, 64, 5).setType(Material.STONE);
        send(anna, "{\"t\":\"info\",\"pos\":[5,64,5]}");
        assertEquals("none", last(anna, "chest").get("status").getAsString());
    }

    @Test
    void friendsThroughUi() {
        send(anna, "{\"t\":\"trust\",\"player\":\"Ben\"}");
        assertTrue(plugin.trust().trusts(anna.getUniqueId(), ben.getUniqueId()));
        send(anna, "{\"t\":\"hello\"}");
        assertTrue(last(anna, "state").getAsJsonArray("friends").toString().contains("Ben"));

        send(anna, "{\"t\":\"untrust\",\"player\":\"Ben\"}");
        assertFalse(plugin.trust().trusts(anna.getUniqueId(), ben.getUniqueId()));
    }
}
