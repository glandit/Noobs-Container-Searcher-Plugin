package de.noobs.containersearcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;

import static de.noobs.containersearcher.Messages.nameOf;

/**
 * Verbindung zum Client-Mod "Noob's Container Searcher".
 *
 * <p>Kanal {@value #CHANNEL}, beide Richtungen. Inhalt: ein UTF-8-String im Minecraft-Format
 * (VarInt-Länge + Bytes) mit einem JSON-Objekt. Feld {@code t} ist der Typ.
 *
 * <pre>
 * Mod → Server
 *   {"t":"hello","v":1}
 *   {"t":"info","pos":[x,y,z]}
 *   {"t":"lock"|"unlock","pos":[x,y,z]}
 *   {"t":"share"|"unshare","pos":[x,y,z],"player":"Name"}
 *   {"t":"trust"|"untrust","player":"Name"}
 *   {"t":"group","op":"create|invite|accept|decline|leave|kick|disband","arg":"..."}
 * Server → Mod
 *   {"t":"hello","v":1}
 *   {"t":"state","group":{...}|null,"invite":{...}|null,"friends":[...],"online":[...]}
 *   {"t":"chest","pos":[x,y,z],"status":"none|unloaded|unlocked|locked",...}
 *   {"t":"result","action":"share","ok":true,"msg":"..."}
 * </pre>
 *
 * Der Server prüft bei jeder Aktion selbst die Rechte – der Mod ist nur die Bedienoberfläche.
 */
public final class ModChannel implements PluginMessageListener, Listener, ClientSync {

    public static final String CHANNEL = "noobs_container_searcher:protect";
    public static final int VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024;
    private static final int MAX_PER_SECOND = 20;
    private static final int MAX_ONLINE_NAMES = 200;

    private final NoobsContainerSearcher plugin;
    private final Set<UUID> clients = new HashSet<>();
    private final Map<UUID, long[]> rate = new HashMap<>();
    private BiConsumer<Player, byte[]> transport;

    public ModChannel(NoobsContainerSearcher plugin) {
        this.plugin = plugin;
        this.transport = (player, bytes) -> deliver(player, bytes, 0);
    }

    /**
     * Wer uns auf dem Kanal schreibt, kann auch Antworten empfangen. NeoForge meldet seine Kanäle bei
     * Nicht-NeoForge-Servern aber nie per minecraft:register an – also tragen wir den Kanal selbst ein
     * (dieselbe Methode, die Paper für minecraft:register benutzt).
     */
    private void ensureListening(Player player) {
        if (player.getListeningPluginChannels().contains(CHANNEL)) {
            return;
        }
        try {
            player.getClass().getMethod("addChannel", String.class).invoke(player, CHANNEL);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().fine("Konnte Kanal für " + player.getName() + " nicht eintragen: " + e);
        }
    }

    /**
     * Paper verwirft Nachrichten auf Kanälen, die der Client (noch) nicht angemeldet hat.
     * Fabric meldet seine Kanäle erst kurz nach dem Join an – also bei Bedarf ein paar Ticks warten.
     */
    private void deliver(Player player, byte[] bytes, int attempt) {
        if (!player.isOnline()) {
            return;
        }
        if (player.getListeningPluginChannels().contains(CHANNEL)) {
            player.sendPluginMessage(plugin, CHANNEL, bytes);
        } else if (attempt < 20) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> deliver(player, bytes, attempt + 1), 2L);
        }
    }

    public void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** Für Tests: Nachrichten abfangen statt an den Client zu schicken. */
    void setTransport(BiConsumer<Player, byte[]> transport) {
        this.transport = transport;
    }

    public boolean isModClient(Player player) {
        return clients.contains(player.getUniqueId());
    }

    // ------------------------------------------------------------------ Empfangen

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!CHANNEL.equals(channel) || message.length > MAX_BYTES || !withinRate(player)) {
            return;
        }
        ensureListening(player);
        JsonObject in;
        try {
            in = JsonParser.parseString(readString(message)).getAsJsonObject();
        } catch (RuntimeException e) {
            return; // kaputte Nachricht ignorieren
        }
        try {
            handle(player, in);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Fehler bei Mod-Nachricht von " + player.getName(), e);
        }
    }

    private void handle(Player player, JsonObject in) {
        String type = string(in, "t");
        clients.add(player.getUniqueId());
        switch (type) {
            case "hello" -> {
                plugin.getLogger().info(player.getName() + " nutzt den Mod Noob's Container Searcher (Gruppe-Tab aktiv).");
                JsonObject hello = message("hello");
                hello.addProperty("v", VERSION);
                send(player, hello);
                sendState(player);
            }
            case "info" -> {
                int[] pos = pos(in);
                if (pos != null) {
                    send(player, chest(player, pos));
                }
            }
            case "lock", "unlock", "share", "unshare" -> chestAction(player, type, in);
            case "trust" -> result(player, type, plugin.groupService().trust(player, string(in, "player")));
            case "untrust" -> result(player, type, plugin.groupService().untrust(player, string(in, "player")));
            case "group" -> groupAction(player, in);
            default -> { }
        }
    }

    private void chestAction(Player player, String type, JsonObject in) {
        int[] pos = pos(in);
        if (pos == null) {
            return;
        }
        Block block = loadedBlock(player, pos);
        if (block == null || !plugin.locks().isLockable(block)) {
            result(player, type, Result.fail("chest-unavailable"));
            send(player, chest(player, pos));
            return;
        }
        ChestService chests = plugin.chestService();
        String target = string(in, "player");
        Result result = switch (type) {
            case "lock" -> chests.lock(player, block);
            case "unlock" -> chests.unlock(player, block);
            case "share" -> chests.share(player, block, target);
            default -> chests.unshare(player, block, target);
        };
        result(player, type, result);
        send(player, chest(player, pos));
    }

    private void groupAction(Player player, JsonObject in) {
        GroupService groups = plugin.groupService();
        String op = string(in, "op");
        String arg = string(in, "arg");
        Result result = switch (op) {
            case "create" -> groups.create(player, arg);
            case "invite" -> groups.invite(player, arg);
            case "accept" -> groups.accept(player);
            case "decline" -> groups.decline(player);
            case "leave" -> groups.leave(player);
            case "kick" -> groups.kick(player, arg);
            case "disband" -> groups.disband(player);
            default -> null;
        };
        if (result != null) {
            result(player, "group:" + op, result);
            sendState(player);
        }
    }

    // ------------------------------------------------------------------ Senden

    @Override
    public void refresh(Collection<UUID> players) {
        for (UUID uuid : Set.copyOf(players)) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && clients.contains(uuid)) {
                sendState(online);
            }
        }
    }

    private void refreshAll() {
        refresh(clients);
    }

    public void sendState(Player player) {
        send(player, state(player));
    }

    JsonObject state(Player player) {
        UUID me = player.getUniqueId();
        JsonObject out = message("state");

        GroupStore.Group group = plugin.groups().groupOf(me);
        if (group == null) {
            out.add("group", JsonNull.INSTANCE);
        } else {
            JsonObject g = new JsonObject();
            g.addProperty("name", group.name());
            g.addProperty("leader", nameOf(group.leader()));
            g.addProperty("me_leader", group.isLeader(me));
            JsonArray members = new JsonArray();
            for (UUID member : group.members()) {
                JsonObject m = new JsonObject();
                m.addProperty("name", nameOf(member));
                m.addProperty("online", Bukkit.getPlayer(member) != null);
                m.addProperty("leader", group.isLeader(member));
                m.addProperty("me", member.equals(me));
                members.add(m);
            }
            g.add("members", members);
            out.add("group", g);
        }

        GroupStore.PendingInvite invite = plugin.groups().pendingInvite(me);
        if (invite == null) {
            out.add("invite", JsonNull.INSTANCE);
        } else {
            JsonObject i = new JsonObject();
            i.addProperty("group", invite.group().name());
            i.addProperty("from", nameOf(invite.inviter()));
            out.add("invite", i);
        }

        out.add("friends", names(plugin.trust().friendsOf(me)));
        JsonArray online = new JsonArray();
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.equals(player) && player.canSee(p))
                .map(Player::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(MAX_ONLINE_NAMES)
                .forEach(online::add);
        out.add("online", online);
        out.addProperty("members_can_break", plugin.settings().groupMembersCanBreak());
        return out;
    }

    JsonObject chest(Player player, int[] pos) {
        JsonObject out = message("chest");
        JsonArray p = new JsonArray();
        for (int value : pos) {
            p.add(value);
        }
        out.add("pos", p);

        Block block = loadedBlock(player, pos);
        if (block == null) {
            out.addProperty("status", "unloaded");
            return out;
        }
        LockManager locks = plugin.locks();
        if (!locks.isLockable(block)) {
            out.addProperty("status", "none");
            return out;
        }
        ContainerLock lock = locks.getLock(block);
        if (lock == null) {
            out.addProperty("status", "unlocked");
            out.addProperty("claimable", plugin.chestService().nearEnough(player, block));
            return out;
        }
        UUID me = player.getUniqueId();
        out.addProperty("status", "locked");
        out.addProperty("owner", nameOf(lock.owner()));
        out.addProperty("mine", lock.isOwner(me));
        out.addProperty("manage", locks.canManage(player, lock));
        out.addProperty("access", locks.canOpen(player, block));
        out.addProperty("reason", reason(me, player, lock));
        if (locks.canOpen(player, block)) {
            out.add("shared", names(lock.shared()));
            GroupStore.Group ownerGroup = plugin.groups().groupOf(lock.owner());
            if (ownerGroup != null) {
                out.addProperty("owner_group", ownerGroup.name());
            }
        }
        return out;
    }

    private String reason(UUID me, Player player, ContainerLock lock) {
        if (lock.isOwner(me)) {
            return "owner";
        }
        if (lock.shared().contains(me)) {
            return "shared";
        }
        if (plugin.groups().sameGroup(lock.owner(), me)) {
            return "group";
        }
        if (plugin.trust().trusts(lock.owner(), me)) {
            return "friend";
        }
        return player.hasPermission(LockManager.ADMIN_PERMISSION) ? "admin" : "none";
    }

    private void result(Player player, String action, Result result) {
        JsonObject out = message("result");
        out.addProperty("action", action);
        out.addProperty("ok", result.ok());
        out.addProperty("msg", plugin.messages().plain(player, result.key(), result.placeholders()));
        send(player, out);
    }

    private void send(Player player, JsonObject message) {
        transport.accept(player, writeString(message.toString()));
    }

    // ------------------------------------------------------------------ Events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refreshAll(); // Online-Liste und grüne Punkte aktualisieren
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clients.remove(uuid);
        rate.remove(uuid);
        Bukkit.getScheduler().runTask(plugin, this::refreshAll);
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private boolean withinRate(Player player) {
        long second = System.currentTimeMillis() / 1000L;
        long[] entry = rate.computeIfAbsent(player.getUniqueId(), k -> new long[]{second, 0});
        if (entry[0] != second) {
            entry[0] = second;
            entry[1] = 0;
        }
        return ++entry[1] <= MAX_PER_SECOND;
    }

    /** Block in der Welt des Spielers, nur wenn der Chunk schon geladen ist (lädt nie selbst Chunks). */
    private @Nullable Block loadedBlock(Player player, int[] pos) {
        World world = player.getWorld();
        if (pos[1] < world.getMinHeight() || pos[1] >= world.getMaxHeight()
                || !world.isChunkLoaded(pos[0] >> 4, pos[2] >> 4)) {
            return null;
        }
        return world.getBlockAt(pos[0], pos[1], pos[2]);
    }

    private static @Nullable int[] pos(JsonObject in) {
        JsonElement element = in.get("pos");
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            return null;
        }
        try {
            JsonArray array = element.getAsJsonArray();
            return new int[]{array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject in, String key) {
        JsonElement element = in.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString().trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static JsonObject message(String type) {
        JsonObject out = new JsonObject();
        out.addProperty("t", type);
        return out;
    }

    private static JsonArray names(Collection<UUID> uuids) {
        JsonArray array = new JsonArray();
        uuids.stream().map(Messages::nameOf).sorted(String.CASE_INSENSITIVE_ORDER).forEach(array::add);
        return array;
    }

    // Minecraft-String: VarInt (Anzahl Bytes) + UTF-8

    static byte[] writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 5);
        int length = bytes.length;
        while ((length & ~0x7F) != 0) {
            out.write((length & 0x7F) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }

    static String readString(byte[] data) {
        int length = 0;
        int shift = 0;
        int index = 0;
        while (true) {
            if (index >= data.length || shift > 28) {
                throw new IllegalArgumentException("Ungültige Länge");
            }
            byte b = data[index++];
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        if (length < 0 || index + length > data.length) {
            throw new IllegalArgumentException("Ungültige Länge");
        }
        return new String(data, index, length, StandardCharsets.UTF_8);
    }
}
