package de.noobs.containersearcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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
 *   {"t":"hello","v":2}
 *   {"t":"sync"}                                   alles noch einmal schicken
 *   {"t":"trust"|"untrust","player":"Name"}
 *   {"t":"group","op":"create|invite|accept|decline|leave|kick|disband","arg":"..."}
 * Server → Mod
 *   {"t":"hello","v":2}
 *   {"t":"state","group":{...}|null,"invite":{...}|null,"friends":[...],"online":[...]}
 *   {"t":"result","action":"group:create","ok":true,"msg":"..."}
 *   {"t":"sync_begin"}  {"t":"containers","list":[C,...]}...  {"t":"sync_end"}
 *   {"t":"container",...C}                          ein Container ist neu oder hat sich geändert
 *   {"t":"container_gone","dim":"...","pos":[x,y,z]}
 *
 *   C = {"dim":"minecraft:overworld","pos":[x,y,z],"pos2":[x,y,z]?,"type":"minecraft:chest",
 *        "owner":"Anna","mine":true,"at":1730000000000,
 *        "items":[{"id":"minecraft:diamond","n":5,"d":"Base64 von ItemStack#serializeAsBytes"}]}
 * </pre>
 *
 * Der Mod nimmt höchstens 65536 Zeichen pro Nachricht an. Längere Nachrichten gehen in Stücken:
 * {@code #frag <id> <nr> <anzahl>\n<teil>} – der Mod setzt sie wieder zusammen.
 *
 * <p>Jeder sieht die Inhalte seiner eigenen Container, die seiner Gruppe und die von Spielern,
 * die ihn als Freund eingetragen haben. Mods mit Version 1 bekommen nur Gruppe und Freunde.
 */
public final class ModChannel implements PluginMessageListener, Listener, ClientSync, ContainerIndex.Listener {

    public static final String CHANNEL = "noobs_container_searcher:protect";
    public static final int VERSION = 2;
    /** Unter der Grenze des Mods (65536 Zeichen), mit Luft für den Stück-Kopf. */
    static final int MAX_CHARS = 60_000;
    private static final int MAX_BYTES = 16 * 1024;
    private static final int MAX_PER_SECOND = 20;
    private static final int MAX_ONLINE_NAMES = 200;
    private static final long SYNC_COOLDOWN_MS = 5_000L;

    private final NoobsContainerSearcher plugin;
    private final Map<UUID, Integer> clients = new HashMap<>();
    private final Map<UUID, long[]> rate = new HashMap<>();
    private final Map<UUID, Long> lastRequestedSync = new HashMap<>();
    private BiConsumer<Player, byte[]> transport;
    private int nextFragment;

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
        return clients.containsKey(player.getUniqueId());
    }

    private boolean syncs(UUID player) {
        return clients.getOrDefault(player, 0) >= 2;
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
        UUID uuid = player.getUniqueId();
        clients.putIfAbsent(uuid, 1);
        switch (type) {
            case "hello" -> {
                int version = Math.max(1, number(in, "v"));
                clients.put(uuid, version);
                plugin.getLogger().info(player.getName() + " uses the mod Noob's Container Searcher"
                        + (version >= 2 ? " (chest sync on)." : " (old version, no chest sync)."));
                JsonObject hello = message("hello");
                hello.addProperty("v", VERSION);
                send(player, hello);
                sendState(player);
                if (mayRequestSync(uuid)) {
                    fullSync(player);
                }
            }
            case "sync" -> {
                if (mayRequestSync(uuid)) {
                    fullSync(player);
                }
            }
            case "trust" -> result(player, type, plugin.groupService().trust(player, string(in, "player")));
            case "untrust" -> result(player, type, plugin.groupService().untrust(player, string(in, "player")));
            case "group" -> groupAction(player, in);
            default -> { }
        }
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

    // ------------------------------------------------------------------ Gruppe, Freunde

    @Override
    public void refresh(Collection<UUID> players) {
        for (UUID uuid : Set.copyOf(players)) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && clients.containsKey(uuid)) {
                sendState(online);
            }
        }
    }

    @Override
    public void resync(Collection<UUID> players) {
        for (UUID uuid : Set.copyOf(players)) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && clients.containsKey(uuid)) {
                sendState(online);
                fullSync(online);
            }
        }
    }

    private void refreshAll() {
        refresh(List.copyOf(clients.keySet()));
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
        // wer mich als Freund eingetragen hat – deren Kisten sehe ich in der Suche
        JsonArray friendOf = new JsonArray();
        for (UUID owner : plugin.trust().ownersTrusting(me)) {
            friendOf.add(nameOf(owner));
        }
        out.add("friend_of", friendOf);
        JsonArray online = new JsonArray();
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.equals(player) && player.canSee(p))
                .map(Player::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(MAX_ONLINE_NAMES)
                .forEach(online::add);
        out.add("online", online);
        return out;
    }

    private void result(Player player, String action, Result result) {
        JsonObject out = message("result");
        out.addProperty("action", action);
        out.addProperty("ok", result.ok());
        out.addProperty("msg", plugin.messages().plain(player, result.key(), result.placeholders()));
        send(player, out);
    }

    // ------------------------------------------------------------------ Kisten-Sync

    /** Sieht {@code viewer} die Container von {@code owner} in der Suche? */
    public boolean canSee(UUID viewer, UUID owner) {
        return viewer.equals(owner)
                || plugin.groups().sameGroup(owner, viewer)
                || plugin.trust().trusts(owner, viewer);
    }

    /** Schickt alles, was der Spieler sehen darf. Der Mod löscht danach gesyncte Einträge, die nicht dabei waren. */
    public void fullSync(Player player) {
        UUID me = player.getUniqueId();
        if (!syncs(me)) {
            return;
        }
        send(player, message("sync_begin"));
        String head = "{\"t\":\"containers\",\"list\":[";
        StringBuilder batch = new StringBuilder(head);
        boolean empty = true;
        for (ContainerSnapshot snapshot : plugin.index().all()) {
            if (!canSee(me, snapshot.owner())) {
                continue;
            }
            String entry = container(snapshot, me).toString();
            if (!empty && batch.length() + entry.length() + 2 > MAX_CHARS) {
                sendRaw(player, batch.append("]}").toString());
                batch.setLength(0);
                batch.append(head);
                empty = true;
            }
            if (!empty) {
                batch.append(',');
            }
            batch.append(entry);
            empty = false;
        }
        if (!empty) {
            sendRaw(player, batch.append("]}").toString());
        }
        send(player, message("sync_end"));
    }

    @Override
    public void changed(@Nullable ContainerSnapshot before, @Nullable ContainerSnapshot after) {
        for (Map.Entry<UUID, Integer> client : List.copyOf(clients.entrySet())) {
            if (client.getValue() < 2) {
                continue;
            }
            UUID viewer = client.getKey();
            Player player = Bukkit.getPlayer(viewer);
            if (player == null) {
                continue;
            }
            if (after != null && canSee(viewer, after.owner())) {
                JsonObject out = container(after, viewer);
                out.addProperty("t", "container");
                send(player, out);
            } else if (before != null && canSee(viewer, before.owner())) {
                send(player, gone(before));
            }
        }
    }

    static JsonObject container(ContainerSnapshot snapshot, UUID viewer) {
        JsonObject out = new JsonObject();
        out.addProperty("dim", snapshot.dimension());
        out.add("pos", pos(snapshot.x(), snapshot.y(), snapshot.z()));
        if (snapshot.second() != null) {
            out.add("pos2", pos(snapshot.second().x(), snapshot.second().y(), snapshot.second().z()));
        }
        out.addProperty("type", snapshot.type());
        out.addProperty("owner", nameOf(snapshot.owner()));
        out.addProperty("mine", snapshot.owner().equals(viewer));
        out.addProperty("at", snapshot.updatedAt());
        JsonArray items = new JsonArray();
        for (ContainerSnapshot.Item item : snapshot.items()) {
            JsonObject i = new JsonObject();
            i.addProperty("id", item.id());
            i.addProperty("n", item.count());
            if (item.data() != null && !item.data().isEmpty()) {
                i.addProperty("d", item.data());
            }
            items.add(i);
        }
        out.add("items", items);
        return out;
    }

    private static JsonObject gone(ContainerSnapshot snapshot) {
        JsonObject out = message("container_gone");
        out.addProperty("dim", snapshot.dimension());
        out.add("pos", pos(snapshot.x(), snapshot.y(), snapshot.z()));
        return out;
    }

    // ------------------------------------------------------------------ Senden

    private void send(Player player, JsonObject message) {
        sendRaw(player, message.toString());
    }

    /** Schickt die Nachricht, zu lange Nachrichten in Stücken. */
    private void sendRaw(Player player, String json) {
        for (String part : fragments(json, ++nextFragment)) {
            transport.accept(player, writeString(part));
        }
    }

    static List<String> fragments(String json, int id) {
        if (json.length() <= MAX_CHARS) {
            return List.of(json);
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < json.length()) {
            int end = Math.min(json.length(), start + MAX_CHARS);
            if (end < json.length() && Character.isHighSurrogate(json.charAt(end - 1))) {
                end--; // Emoji & Co. nicht zerschneiden
            }
            chunks.add(json.substring(start, end));
            start = end;
        }
        List<String> out = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            out.add("#frag " + id + " " + i + " " + chunks.size() + "\n" + chunks.get(i));
        }
        return out;
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
        lastRequestedSync.remove(uuid);
        Bukkit.getScheduler().runTask(plugin, this::refreshAll);
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    /** Ein kompletter Sync kostet viel – auf Wunsch des Clients höchstens alle paar Sekunden. */
    private boolean mayRequestSync(UUID player) {
        long now = System.currentTimeMillis();
        Long last = lastRequestedSync.get(player);
        if (last != null && now - last < SYNC_COOLDOWN_MS) {
            return false;
        }
        lastRequestedSync.put(player, now);
        return true;
    }

    private boolean withinRate(Player player) {
        long second = System.currentTimeMillis() / 1000L;
        long[] entry = rate.computeIfAbsent(player.getUniqueId(), k -> new long[]{second, 0});
        if (entry[0] != second) {
            entry[0] = second;
            entry[1] = 0;
        }
        return ++entry[1] <= MAX_PER_SECOND;
    }

    private static JsonArray pos(int x, int y, int z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    private static String string(JsonObject in, String key) {
        JsonElement element = in.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString().trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static int number(JsonObject in, String key) {
        JsonElement element = in.get(key);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
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
