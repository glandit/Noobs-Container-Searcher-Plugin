package de.noobs.containersearcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Besitz, Inhalts-Index und Sync an den Mod – der Mod wird über den Kanal simuliert. */
class SyncTest {

    private ServerMock server;
    private NoobsContainerSearcher plugin;
    private Ownership ownership;
    private ContainerIndex index;
    private ModChannel channel;
    private WorldMock world;
    private PlayerMock anna;
    private PlayerMock ben;
    private PlayerMock carl;
    private final Map<Player, List<JsonObject>> inbox = new HashMap<>();
    private final Map<Player, List<String>> raw = new HashMap<>();
    private final Map<String, String[]> fragments = new HashMap<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(NoobsContainerSearcher.class);
        ownership = plugin.ownership();
        index = plugin.index();
        // MockBukkit kann keine Items serialisieren – Typ und Anzahl reichen zum Prüfen
        index.setEncoder(stack -> stack.getType().name() + "x" + stack.getAmount());
        channel = plugin.modChannel();
        channel.setTransport(this::receive);
        world = server.addSimpleWorld("world");
        world.loadChunk(0, 0);
        anna = server.addPlayer("Anna");
        ben = server.addPlayer("Ben");
        carl = server.addPlayer("Carl");
        for (PlayerMock player : List.of(anna, ben, carl)) {
            player.teleport(new Location(world, 0.5, 64, 2.5));
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------ simulierter Mod

    /** Wie der Mod: Stücke zusammensetzen, dann JSON lesen. */
    private void receive(Player player, byte[] bytes) {
        String text = ModChannel.readString(bytes);
        raw.computeIfAbsent(player, k -> new ArrayList<>()).add(text);
        if (text.startsWith("#frag ")) {
            int newline = text.indexOf('\n');
            String[] head = text.substring(6, newline).split(" ");
            String id = player.getName() + "/" + head[0];
            String[] parts = fragments.computeIfAbsent(id, k -> new String[Integer.parseInt(head[2])]);
            parts[Integer.parseInt(head[1])] = text.substring(newline + 1);
            for (String part : parts) {
                if (part == null) {
                    return;
                }
            }
            fragments.remove(id);
            text = String.join("", parts);
        }
        inbox.computeIfAbsent(player, k -> new ArrayList<>()).add(JsonParser.parseString(text).getAsJsonObject());
    }

    private void hello(Player player) {
        channel.onPluginMessageReceived(ModChannel.CHANNEL, player, ModChannel.writeString("{\"t\":\"hello\",\"v\":2}"));
    }

    private List<JsonObject> messages(Player player, String type) {
        return inbox.getOrDefault(player, List.of()).stream()
                .filter(m -> m.get("t").getAsString().equals(type)).toList();
    }

    /** Container aus dem letzten vollständigen Sync (sync_begin … sync_end). */
    /** Komplette Syncs kommen höchstens alle 5 Sekunden pro Spieler – so lange warten. */
    private void waitForSyncs() {
        server.getScheduler().performTicks(ModChannel.SYNC_COOLDOWN_TICKS + 21);
    }

    private long syncCount(Player player) {
        return inbox.getOrDefault(player, List.of()).stream()
                .filter(m -> m.get("t").getAsString().equals("sync_begin")).count();
    }

    private List<JsonObject> lastSync(Player player) {
        List<JsonObject> all = inbox.getOrDefault(player, List.of());
        int begin = -1;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).get("t").getAsString().equals("sync_begin")) {
                begin = i;
                break;
            }
        }
        assertTrue(begin >= 0, "kein Sync bei " + player.getName());
        List<JsonObject> containers = new ArrayList<>();
        for (int i = begin + 1; i < all.size(); i++) {
            JsonObject m = all.get(i);
            String type = m.get("t").getAsString();
            if (type.equals("sync_end")) {
                return containers;
            }
            if (type.equals("containers")) {
                for (JsonElement e : m.getAsJsonArray("list")) {
                    containers.add(e.getAsJsonObject());
                }
            }
        }
        throw new AssertionError("Sync nicht abgeschlossen");
    }

    private void clearInbox() {
        inbox.clear();
        raw.clear();
    }

    // ------------------------------------------------------------------ Welt

    private Block chest(int x) {
        return chest(world.getBlockAt(x, 64, 0));
    }

    private Block chest(Block block) {
        block.setType(Material.CHEST);
        return block;
    }

    private Block chestOf(PlayerMock owner, int x, ItemStack... items) {
        return chestOf(owner, world.getBlockAt(x, 64, 0), items);
    }

    private Block chestOf(PlayerMock owner, Block at, ItemStack... items) {
        Block block = chest(at);
        ownership.setOwner(block, owner.getUniqueId());
        ((Container) block.getState(false)).getInventory().addItem(items);
        index.markDirty(block);
        index.processAll();
        return block;
    }

    private static String items(JsonObject container) {
        StringBuilder out = new StringBuilder();
        for (JsonElement e : container.getAsJsonArray("items")) {
            JsonObject item = e.getAsJsonObject();
            out.append(item.get("id").getAsString()).append('=').append(item.get("n").getAsInt()).append(' ');
        }
        return out.toString().trim();
    }

    private static int[] pos(JsonObject container) {
        JsonArray p = container.getAsJsonArray("pos");
        return new int[]{p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()};
    }

    // ------------------------------------------------------------------ Besitz

    @Test
    void placingAChestMakesItYours() {
        Block block = chest(0);
        server.getPluginManager().callEvent(new BlockPlaceEvent(block, block.getState(), world.getBlockAt(0, 63, 0),
                new ItemStack(Material.CHEST), anna, true, EquipmentSlot.HAND));
        assertEquals(anna.getUniqueId(), ownership.owner(block));
        index.processAll();
        assertNotNull(index.get(block), "neue Truhe ist im Index");
    }

    private Block chestHalf(int x, Chest.Type type) {
        Block block = chest(x);
        Chest data = (Chest) block.getBlockData();
        data.setFacing(BlockFace.NORTH);
        data.setType(type);
        block.setBlockData(data);
        return block;
    }

    @Test
    void extendingAChestKeepsItsOwner() {
        Block right = chestHalf(1, Chest.Type.RIGHT);
        ownership.setOwner(right, anna.getUniqueId());
        // MockBukkit setzt beim Speichern des Block-Zustands die Doppeltruhen-Form zurück – neu setzen
        Chest data = (Chest) right.getBlockData();
        data.setFacing(BlockFace.NORTH);
        data.setType(Chest.Type.RIGHT);
        right.setBlockData(data);
        assertEquals(anna.getUniqueId(), ownership.readOwner(right));
        Block left = chestHalf(0, Chest.Type.LEFT);
        assertEquals(left, ownership.canonical(right), "gespeichert unter der kleineren Position, wie im Mod");
        server.getPluginManager().callEvent(new BlockPlaceEvent(left, left.getState(), world.getBlockAt(0, 63, 0),
                new ItemStack(Material.CHEST), ben, true, EquipmentSlot.HAND));
        assertEquals(anna.getUniqueId(), ownership.readOwner(left), "Ben erweitert nur – Truhe bleibt Annas");
    }

    @Test
    void nothingIsProtected() {
        Block block = chestOf(anna, 0);
        org.bukkit.event.player.PlayerInteractEvent open = new org.bukkit.event.player.PlayerInteractEvent(
                ben, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(open);
        assertTrue(open.useInteractedBlock() != org.bukkit.event.Event.Result.DENY, "Ben darf Annas Truhe öffnen");
        org.bukkit.event.block.BlockBreakEvent breaking = new org.bukkit.event.block.BlockBreakEvent(block, ben);
        server.getPluginManager().callEvent(breaking);
        assertFalse(breaking.isCancelled(), "und abbauen");
    }

    @Test
    void firstOpenerClaimsOldChests() {
        Block block = chest(0);
        ben.openInventory(((Container) block.getState(false)).getInventory());
        assertEquals(ben.getUniqueId(), ownership.owner(block));
        ben.closeInventory();

        anna.openInventory(((Container) block.getState(false)).getInventory());
        assertEquals(ben.getUniqueId(), ownership.owner(block), "bleibt Bens");
    }

    @Test
    void claimOnFirstOpenCanBeTurnedOff() {
        plugin.getConfig().set("claim-on-first-open", false);
        plugin.saveConfig();
        plugin.reload();
        Block block = chest(0);
        ben.openInventory(((Container) block.getState(false)).getInventory());
        assertNull(ownership.owner(block));
    }

    // ------------------------------------------------------------------ Sync

    @Test
    void ownChestsSyncWithoutOpeningThem() {
        chestOf(anna, 0, new ItemStack(Material.DIAMOND, 5), new ItemStack(Material.STONE, 64));
        hello(anna);
        List<JsonObject> synced = lastSync(anna);
        assertEquals(1, synced.size());
        JsonObject chest = synced.get(0);
        assertEquals("minecraft:diamond=5 minecraft:stone=64", items(chest));
        assertTrue(chest.get("mine").getAsBoolean());
        assertEquals("Anna", chest.get("owner").getAsString());
        assertEquals("minecraft:chest", chest.get("type").getAsString());
        assertEquals("DIAMONDx5", chest.getAsJsonArray("items").get(0).getAsJsonObject().get("d").getAsString());
    }

    @Test
    void strangersSeeNothing() {
        chestOf(anna, 0, new ItemStack(Material.DIAMOND, 5));
        hello(ben);
        assertTrue(lastSync(ben).isEmpty());
    }

    @Test
    void groupMembersSeeEachOthersChests() {
        chestOf(anna, 0, new ItemStack(Material.DIAMOND, 5));
        chestOf(ben, 2, new ItemStack(Material.GOLD_INGOT, 3));
        hello(anna);
        hello(ben);
        anna.performCommand("gruppe erstellen Noobs");
        anna.performCommand("gruppe einladen Ben");
        ben.performCommand("gruppe annehmen");
        waitForSyncs();

        assertEquals(2, lastSync(ben).size(), "Ben bekommt Annas Truhe dazu");
        assertEquals(2, lastSync(anna).size(), "Anna bekommt Bens Truhe dazu");
        JsonObject annas = lastSync(ben).stream().filter(c -> c.get("owner").getAsString().equals("Anna")).findFirst().orElseThrow();
        assertFalse(annas.get("mine").getAsBoolean());

        ben.performCommand("gruppe verlassen");
        waitForSyncs();
        assertEquals(1, lastSync(ben).size(), "nach dem Verlassen nur noch die eigene");
        assertEquals("Ben", lastSync(ben).get(0).get("owner").getAsString());
    }

    @Test
    void friendsSeeYourChestsButNotTheOtherWayRound() {
        chestOf(anna, 0, new ItemStack(Material.DIAMOND, 5));
        chestOf(carl, 2, new ItemStack(Material.EMERALD, 1));
        hello(anna);
        hello(carl);
        anna.performCommand("truhe vertrauen Carl");
        waitForSyncs();
        List<JsonObject> forCarl = lastSync(carl);
        assertEquals(2, forCarl.size(), "Carl sieht Annas und seine eigene");
        assertEquals(1, lastSync(anna).size(), "Anna sieht Carls nicht – nur Carl ist Annas Freund");

        anna.performCommand("truhe misstrauen Carl");
        waitForSyncs();
        assertEquals(1, lastSync(carl).size());
    }

    /** Freund an/aus im Sekundentakt löst nicht jedes Mal einen kompletten Sync aus. */
    @Test
    void togglingFriendsDoesNotFloodFullSyncs() {
        chestOf(anna, 0, new ItemStack(Material.DIAMOND, 5));
        hello(anna);
        hello(carl);
        long before = syncCount(carl);
        for (int i = 0; i < 10; i++) {
            anna.performCommand("truhe vertrauen Carl");
            anna.performCommand("truhe misstrauen Carl");
        }
        anna.performCommand("truhe vertrauen Carl");
        assertEquals(before, syncCount(carl), "während der Pause noch kein neuer Sync");
        waitForSyncs();
        assertEquals(before + 1, syncCount(carl), "danach genau einer, mit dem letzten Stand");
        assertEquals(1, lastSync(carl).size(), "Carl sieht Annas Truhe");
    }

    @Test
    void changesArriveLiveOnlyAtThoseWhoMaySee() {
        Block block = chestOf(anna, 0, new ItemStack(Material.DIAMOND, 5));
        hello(anna);
        hello(ben);
        clearInbox();

        ((Container) block.getState(false)).getInventory().addItem(new ItemStack(Material.APPLE, 2));
        index.markDirty(block);
        index.processAll();
        List<JsonObject> updates = messages(anna, "container");
        assertEquals(1, updates.size());
        assertEquals("minecraft:diamond=5 minecraft:apple=2", items(updates.get(0)));
        assertTrue(messages(ben, "container").isEmpty());

        // nichts geändert → nichts geschickt
        index.markDirty(block);
        index.processAll();
        assertEquals(1, messages(anna, "container").size());

        block.setType(Material.AIR);
        index.markDirty(block);
        index.processAll();
        List<JsonObject> gone = messages(anna, "container_gone");
        assertEquals(1, gone.size());
        assertEquals(0, pos(gone.get(0))[0]);
        assertNull(index.get(block));
    }

    @Test
    void hopperMovesAndClosingTheChestTriggerAnUpdate() {
        Block block = chestOf(anna, 0);
        hello(anna);
        clearInbox();
        Container container = (Container) block.getState(false);
        ben.openInventory(container.getInventory());
        container.getInventory().addItem(new ItemStack(Material.BREAD, 4));
        ben.closeInventory();
        server.getScheduler().performTicks(10);
        assertEquals("minecraft:bread=4", items(messages(anna, "container").get(0)));
    }

    @Test
    void oldModVersionsGetNoChestSync() {
        chestOf(anna, 0, new ItemStack(Material.DIAMOND, 5));
        channel.onPluginMessageReceived(ModChannel.CHANNEL, anna, ModChannel.writeString("{\"t\":\"hello\",\"v\":1}"));
        assertFalse(messages(anna, "state").isEmpty());
        assertTrue(messages(anna, "sync_begin").isEmpty());
    }

    @Test
    void bigSyncsStayUnderTheModsLimit() {
        // lange Item-Daten wie bei vollen Shulkerkisten
        index.setEncoder(stack -> "x".repeat(3000));
        for (int x = 0; x < 40; x++) {
            ItemStack[] items = new ItemStack[27];
            for (int i = 0; i < items.length; i++) {
                items[i] = new ItemStack(Material.SHULKER_BOX);
            }
            chestOf(anna, world.getBlockAt((x % 8) * 2, 64, (x / 8) * 2), items); // alles in Chunk 0,0
        }
        hello(anna);
        assertEquals(40, lastSync(anna).size());
        for (String message : raw.get(anna)) {
            assertTrue(message.length() <= 65_536, "Nachricht zu lang: " + message.length());
        }
        assertTrue(raw.get(anna).stream().anyMatch(m -> m.startsWith("#frag ")), "eine Truhe allein ist zu groß → Stücke");
    }

    /**
     * Chunks, die beim Start schon geladen sind (Spawn, forceload, /reload), bekommen kein ChunkLoadEvent:
     * ihre Container werden beim Start trotzdem neu gelesen. (Alte Sperren aus 1.0.0 findet derselbe Weg über
     * getTileEntities – das kann MockBukkit nicht, das ist auf einem echten Server geprüft.)
     */
    @Test
    void containersInChunksLoadedBeforeTheStartAreReadAgain() {
        Block block = chestOf(anna, 4, new ItemStack(Material.DIAMOND, 5));
        server.getPluginManager().disablePlugin(plugin);
        ((Container) block.getState(false)).getInventory().addItem(new ItemStack(Material.EMERALD, 2)); // Plugin aus: kein Event
        server.getPluginManager().enablePlugin(plugin);
        ContainerIndex restarted = plugin.index();
        restarted.setEncoder(stack -> stack.getType().name() + "x" + stack.getAmount());
        restarted.processAll();
        ContainerSnapshot snapshot = restarted.get(block);
        assertTrue(snapshot != null);
        assertTrue(snapshot.items().stream().anyMatch(item -> item.id().contains("emerald")),
                "Änderung aus der Zeit ohne Plugin fehlt: " + snapshot.items());
    }

    @Test
    void fragmentsRoundTrip() {
        String json = "{\"t\":\"x\",\"d\":\"" + "ab😀".repeat(40_000) + "\"}";
        List<String> parts = ModChannel.fragments(json, 7);
        assertTrue(parts.size() > 1);
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            assertTrue(part.length() <= ModChannel.MAX_CHARS + 32);
            joined.append(part.substring(part.indexOf('\n') + 1));
        }
        assertEquals(json, joined.toString());
        assertEquals(List.of("{}"), ModChannel.fragments("{}", 8));
    }

    @Test
    void indexSurvivesRestart() {
        chestOf(anna, 0, new ItemStack(Material.DIAMOND, 5));
        index.saveNow();
        ContainerIndex reloaded = new ContainerIndex(plugin, ownership, new File(plugin.getDataFolder(), "containers.json.gz"));
        reloaded.load();
        assertEquals(1, reloaded.all().size());
        ContainerSnapshot snapshot = reloaded.all().iterator().next();
        assertEquals(anna.getUniqueId(), snapshot.owner());
        assertEquals("minecraft:diamond", snapshot.items().get(0).id());
    }

    // ------------------------------------------------------------------ /truhe info

    private String infoText(PlayerMock player, Block block) {
        hello(player); // /truhe gibt es nur mit dem Mod
        plugin.setTargetFinder(p -> block);
        while (player.nextComponentMessage() != null) {
            // alte Nachrichten verwerfen
        }
        player.performCommand("ncs info");
        StringBuilder text = new StringBuilder();
        Component line;
        while ((line = player.nextComponentMessage()) != null) {
            text.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return text.toString();
    }

    @Test
    void infoShowsOwnerAndOnlyTheOwnerSeesFriends() {
        Block block = chestOf(anna, 0, new ItemStack(Material.DIAMOND, 5));
        plugin.trust().add(anna.getUniqueId(), carl.getUniqueId());

        String owner = infoText(anna, block);
        assertTrue(owner.contains("Anna") && owner.contains("Carl") && owner.contains("1"), owner);

        String stranger = infoText(ben, block);
        assertTrue(stranger.contains("Anna") && stranger.contains("no"), stranger);
        assertFalse(stranger.contains("Carl"), "Fremde sehen Annas Freunde nicht: " + stranger);

        String friend = infoText(carl, block);
        assertTrue(friend.contains("yes"), friend);

        String unowned = infoText(ben, chest(4));
        assertTrue(unowned.contains("no owner"), unowned);
    }

    @Test
    void viewerRulesMatchGroupAndTrust() {
        UUID a = anna.getUniqueId();
        UUID b = ben.getUniqueId();
        assertTrue(channel.canSee(a, a));
        assertFalse(channel.canSee(b, a));
        plugin.trust().add(a, b);
        assertTrue(channel.canSee(b, a));
        assertFalse(channel.canSee(a, b));
    }
}
