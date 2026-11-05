package de.noobs.containersearcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Simuliert den Client-Mod: schickt JSON über den Kanal und prüft die Antworten (Gruppe, Freunde). */
class ModChannelTest {

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
        ben = server.addPlayer("Ben");
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

    @Test
    void stringCodecRoundTrip() {
        String longText = "ä".repeat(300) + "x".repeat(20000);
        assertEquals(longText, ModChannel.readString(ModChannel.writeString(longText)));
        assertEquals("", ModChannel.readString(ModChannel.writeString("")));
    }

    @Test
    void helloAnswersWithHelloAndState() {
        send(anna, "{\"t\":\"hello\",\"v\":2}");
        assertEquals(2, last(anna, "hello").get("v").getAsInt());
        JsonObject state = last(anna, "state");
        assertNotNull(state);
        assertTrue(state.get("group").isJsonNull());
        assertTrue(state.getAsJsonArray("online").toString().contains("Ben"));
        assertNotNull(last(anna, "sync_end"), "Kisten-Sync gleich nach dem Hallo");
    }

    @Test
    void groupFlowThroughUi() {
        send(anna, "{\"t\":\"hello\",\"v\":2}");
        send(ben, "{\"t\":\"hello\",\"v\":2}");

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
    void friendsThroughUi() {
        send(anna, "{\"t\":\"hello\",\"v\":2}");
        send(ben, "{\"t\":\"hello\",\"v\":2}");
        send(anna, "{\"t\":\"trust\",\"player\":\"Ben\"}");
        assertTrue(plugin.trust().trusts(anna.getUniqueId(), ben.getUniqueId()));
        assertTrue(last(anna, "state").getAsJsonArray("friends").toString().contains("Ben"));
        assertTrue(last(ben, "state").getAsJsonArray("friend_of").toString().contains("Anna"),
                "Ben sieht, wessen Kisten er bekommt");

        send(anna, "{\"t\":\"untrust\",\"player\":\"Ben\"}");
        assertFalse(plugin.trust().trusts(anna.getUniqueId(), ben.getUniqueId()));
    }

    @Test
    void syncRequestsAreThrottled() {
        send(anna, "{\"t\":\"hello\",\"v\":2}");
        inbox.clear();
        send(anna, "{\"t\":\"sync\"}");
        assertTrue(inbox.getOrDefault(anna, List.of()).isEmpty(), "gleich nach dem Hallo kein zweiter Sync");
    }

    @Test
    void badInputIsIgnored() {
        channel.onPluginMessageReceived(ModChannel.CHANNEL, anna, new byte[]{(byte) 0xFF, (byte) 0xFF});
        send(anna, "kein json");
        send(anna, "{\"t\":\"group\",\"op\":\"gibtsnicht\"}");
        send(anna, "{\"t\":\"lock\",\"pos\":[0,64,0]}"); // alte Mod-Version
        assertTrue(inbox.getOrDefault(anna, List.of()).isEmpty());
    }
}
