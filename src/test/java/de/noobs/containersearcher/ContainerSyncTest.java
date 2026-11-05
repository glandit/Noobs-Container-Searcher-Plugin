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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Truheninhalte unter Freunden und Gruppenmitgliedern synchronisieren. */
class ContainerSyncTest {

    private static final String SYNC =
            "{\"t\":\"sync\",\"pos\":[0,64,0],\"dim\":\"minecraft:overworld\",\"data\":\"AAAA\"}";

    private ServerMock server;
    private NoobsContainerSearcher plugin;
    private ModChannel channel;
    private WorldMock world;
    private PlayerMock anna;
    private PlayerMock ben;
    private PlayerMock carl;
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
        carl = server.addPlayer("Carl");
        anna.teleport(new Location(world, 0.5, 64, 2.5));
        ben.teleport(new Location(world, 40.5, 64, 40.5));
        carl.teleport(new Location(world, 41.5, 64, 40.5));
        world.loadChunk(0, 0);
        for (Player player : List.of(anna, ben, carl)) {
            send(player, "{\"t\":\"hello\",\"v\":1}");
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void send(Player player, String json) {
        channel.onPluginMessageReceived(ModChannel.CHANNEL, player, ModChannel.writeString(json));
    }

    private int syncs(Player player) {
        return (int) inbox.getOrDefault(player, List.of()).stream()
                .filter(m -> m.get("t").getAsString().equals("sync")).count();
    }

    private JsonObject lastSync(Player player) {
        List<JsonObject> messages = inbox.getOrDefault(player, List.of());
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).get("t").getAsString().equals("sync")) {
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
    void friendReceivesOpenedChest() {
        chest();
        plugin.trust().add(anna.getUniqueId(), ben.getUniqueId()); // Anna vertraut Ben
        send(anna, SYNC);

        JsonObject received = lastSync(ben);
        assertNotNull(received);
        assertEquals("Anna", received.get("by").getAsString());
        assertEquals("AAAA", received.get("data").getAsString());
        assertEquals("minecraft:overworld", received.get("dim").getAsString());
        assertEquals(0, received.getAsJsonArray("pos").get(0).getAsInt());
        assertEquals(0, syncs(anna), "the sender gets nothing back");
        assertEquals(0, syncs(carl), "strangers get nothing");
    }

    @Test
    void groupMemberReceivesOpenedChest() {
        chest();
        send(anna, "{\"t\":\"group\",\"op\":\"create\",\"arg\":\"Noobs\"}");
        send(anna, "{\"t\":\"group\",\"op\":\"invite\",\"arg\":\"Carl\"}");
        send(carl, "{\"t\":\"group\",\"op\":\"accept\"}");
        send(anna, SYNC);

        assertEquals(1, syncs(carl));
        assertEquals(0, syncs(ben));
    }

    @Test
    void lockedChestOnlyGoesToThoseWhoMayOpenIt() {
        Block block = chest();
        plugin.locks().setLock(block, ContainerLock.ownedBy(anna.getUniqueId()));
        // Ben vertraut Anna, aber Anna hat Ben nichts freigegeben: Ben darf Annas Truhe nicht öffnen
        plugin.trust().add(ben.getUniqueId(), anna.getUniqueId());
        send(anna, SYNC);
        assertEquals(0, syncs(ben));

        // jetzt vertraut Anna Ben
        plugin.trust().add(anna.getUniqueId(), ben.getUniqueId());
        send(anna, SYNC);
        assertEquals(1, syncs(ben));
    }

    @Test
    void chestOfSomeoneElseIsNotRelayedByAStranger() {
        Block block = chest();
        plugin.locks().setLock(block, ContainerLock.ownedBy(carl.getUniqueId()));
        plugin.trust().add(anna.getUniqueId(), ben.getUniqueId());
        send(anna, SYNC); // Anna darf Carls Truhe nicht öffnen, also auch nichts weitergeben
        assertEquals(0, syncs(ben));
        assertEquals(0, plugin.containerSync().size());
    }

    @Test
    void tooFarAwayIsIgnored() {
        chest();
        plugin.trust().add(anna.getUniqueId(), ben.getUniqueId());
        anna.teleport(new Location(world, 0.5, 64, 30.5));
        send(anna, SYNC);
        assertEquals(0, syncs(ben));
    }

    @Test
    void laterJoinerGetsTheSavedState() {
        chest();
        plugin.trust().add(anna.getUniqueId(), ben.getUniqueId());
        send(anna, SYNC);
        inbox.remove(ben);
        assertEquals(1, plugin.containerSync().size());

        send(ben, "{\"t\":\"hello\",\"v\":1}"); // Ben kommt (erneut) an
        server.getScheduler().performTicks(40);
        assertEquals(1, syncs(ben));
        assertEquals(0, syncs(carl));
    }

    @Test
    void breakingTheChestForgetsIt() {
        Block block = chest();
        plugin.trust().add(anna.getUniqueId(), ben.getUniqueId());
        send(anna, SYNC);
        assertEquals(1, plugin.containerSync().size());

        server.getPluginManager().callEvent(new org.bukkit.event.block.BlockBreakEvent(block, anna));
        assertEquals(0, plugin.containerSync().size());
    }

    @Test
    void oversizedAndBrokenMessagesAreIgnored() {
        chest();
        plugin.trust().add(anna.getUniqueId(), ben.getUniqueId());
        send(anna, "{\"t\":\"sync\",\"pos\":[0,64,0],\"dim\":\"x\",\"data\":\"" + "A".repeat(ContainerSync.MAX_DATA_CHARS + 5) + "\"}");
        send(anna, "{\"t\":\"sync\",\"pos\":[0,64],\"dim\":\"x\",\"data\":\"AAAA\"}");
        send(anna, "{\"t\":\"sync\",\"pos\":[0,64,0],\"dim\":\"\",\"data\":\"AAAA\"}");
        assertEquals(0, syncs(ben));
        assertNull(lastSync(ben));
    }

    @Test
    void canBeSwitchedOff() {
        chest();
        plugin.trust().add(anna.getUniqueId(), ben.getUniqueId());
        plugin.getConfig().set("sync.enabled", false);
        send(anna, SYNC);
        assertEquals(0, syncs(ben));
        assertTrue(plugin.containerSync().size() == 0);
    }
}
