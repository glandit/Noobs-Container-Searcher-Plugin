package de.noobs.containersearcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Truheninhalte unter Freunden und Gruppenmitgliedern synchronisieren.
 *
 * <p>Der Mod des Spielers, der eine Truhe öffnet, schickt ihren Inhalt ({@code t:"sync"}, komprimiert und
 * base64-kodiert; der Server liest ihn nicht, er leitet ihn nur weiter). Der Server prüft, dass der Absender die
 * Truhe öffnen darf und in der Nähe steht, merkt sich den letzten Stand und schickt ihn an alle, die
 * <ul>
 *   <li>mit dem Absender befreundet sind (einer vertraut dem anderen) oder in derselben Gruppe sind und</li>
 *   <li>die Truhe selbst öffnen dürften.</li>
 * </ul>
 * Wer später den Server betritt, bekommt den gemerkten Stand beim Hallo des Mods nachgeschickt.
 * Beim Abbauen einer Truhe wird der gemerkte Stand gelöscht.
 */
public final class ContainerSync implements Listener {

    /** Obergrenze für das Datenfeld; eine Plugin-Nachricht darf höchstens ~32 KB groß sein. */
    static final int MAX_DATA_CHARS = 28_000;
    private static final int MAX_DISTANCE = 12;
    private static final int SEND_PER_TICK = 4;
    private static final Type FILE_TYPE = new TypeToken<Map<String, Entry>>() { }.getType();
    private static final Gson GSON = new GsonBuilder().create();

    /** Gemerkter Truhenstand. {@code owner} ist null bei ungesicherten Truhen. */
    static final class Entry {
        String world;
        int x;
        int y;
        int z;
        String dim;
        String by;
        String byName;
        String owner;
        List<String> shared = new ArrayList<>();
        long ts;
        String data;
    }

    private final NoobsContainerSearcher plugin;
    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean dirty;

    public ContainerSync(NoobsContainerSearcher plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "sync.json");
    }

    public void register() {
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveIfDirty, 20L * 60, 20L * 60);
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("sync.enabled", true);
    }

    private int maxEntries() {
        return Math.max(10, plugin.getConfig().getInt("sync.max-entries", 5000));
    }

    // ------------------------------------------------------------------ Empfangen vom Mod

    /** {@code {"t":"sync","pos":[x,y,z],"dim":"...","data":"..."}} */
    void receive(Player sender, JsonObject in) {
        if (!enabled()) {
            return;
        }
        int[] pos = pos(in);
        String dim = text(in, "dim", 128);
        String data = text(in, "data", MAX_DATA_CHARS + 1);
        if (pos == null || dim.isEmpty() || data.isEmpty() || data.length() > MAX_DATA_CHARS) {
            return;
        }
        World world = sender.getWorld();
        if (pos[1] < world.getMinHeight() || pos[1] >= world.getMaxHeight()
                || !world.isChunkLoaded(pos[0] >> 4, pos[2] >> 4)) {
            return;
        }
        Block block = world.getBlockAt(pos[0], pos[1], pos[2]);
        LockManager locks = plugin.locks();
        if (!locks.isLockable(block) || !locks.canOpen(sender, block)
                || block.getLocation().distanceSquared(sender.getLocation()) > MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }

        // wem die Truhe gehört und wer sie öffnen darf, merken wir uns mit dem Stand (für Spieler, die erst später kommen)
        ContainerLock lock = locks.getLock(block);
        List<Block> halves = locks.halves(block);
        Entry entry = new Entry();
        entry.world = world.getName();
        entry.x = pos[0];
        entry.y = pos[1];
        entry.z = pos[2];
        entry.dim = dim;
        entry.by = sender.getUniqueId().toString();
        entry.byName = sender.getName();
        entry.ts = System.currentTimeMillis();
        entry.data = data;
        if (lock != null) {
            entry.owner = lock.owner().toString();
            for (UUID uuid : lock.shared()) {
                entry.shared.add(uuid.toString());
            }
        }
        synchronized (entries) {
            // beide Hälften einer Doppeltruhe teilen sich einen Stand, den zuletzt gesehenen
            for (Block half : halves) {
                entries.remove(key(world.getName(), half.getX(), half.getY(), half.getZ()));
            }
            entries.put(key(world.getName(), pos[0], pos[1], pos[2]), entry);
            trim();
            dirty = true;
        }
        broadcast(sender, entry);
    }

    private void broadcast(Player sender, Entry entry) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(sender) && wants(sender.getUniqueId(), other.getUniqueId(), entry)) {
                deliver(other, entry);
            }
        }
    }

    // ------------------------------------------------------------------ Nachschicken beim Beitritt

    /** Schickt dem Spieler alle gemerkten Stände, die er sehen darf (nach und nach, wenige pro Tick). */
    void sendAll(Player player) {
        if (!enabled()) {
            return;
        }
        List<Entry> due = new ArrayList<>();
        UUID me = player.getUniqueId();
        synchronized (entries) {
            for (Entry entry : entries.values()) {
                if (!entry.by.equals(me.toString()) && wants(UUID.fromString(entry.by), me, entry)) {
                    due.add(entry);
                }
            }
        }
        if (due.isEmpty()) {
            return;
        }
        int[] next = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline()) {
                task.cancel();
                return;
            }
            for (int i = 0; i < SEND_PER_TICK && next[0] < due.size(); i++) {
                deliver(player, due.get(next[0]++));
            }
            if (next[0] >= due.size()) {
                task.cancel();
            }
        }, 20L, 1L);
    }

    // ------------------------------------------------------------------ Regeln

    /** Soll {@code receiver} diesen von {@code sender} gesehenen Truhenstand bekommen? */
    boolean wants(UUID sender, UUID receiver, Entry entry) {
        if (sender.equals(receiver) || !related(sender, receiver)) {
            return false;
        }
        if (entry.owner == null) {
            return true; // ungesicherte Truhe: darf jeder öffnen
        }
        Set<UUID> shared = new HashSet<>();
        for (String raw : entry.shared) {
            UUID uuid = parse(raw);
            if (uuid != null) {
                shared.add(uuid);
            }
        }
        UUID owner = parse(entry.owner);
        return owner != null && plugin.locks().hasAccess(receiver, new ContainerLock(owner, shared));
    }

    /** Befreundet: einer vertraut dem anderen, oder beide sind in einer Gruppe. */
    boolean related(UUID a, UUID b) {
        return plugin.trust().trusts(a, b) || plugin.trust().trusts(b, a) || plugin.groups().sameGroup(a, b);
    }

    // ------------------------------------------------------------------ Senden

    private void deliver(Player player, Entry entry) {
        ModChannel channel = plugin.modChannel();
        if (channel == null || !channel.isModClient(player)) {
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("t", "sync");
        JsonArray pos = new JsonArray();
        pos.add(entry.x);
        pos.add(entry.y);
        pos.add(entry.z);
        out.add("pos", pos);
        out.addProperty("dim", entry.dim);
        out.addProperty("by", entry.byName);
        out.addProperty("ts", entry.ts);
        out.addProperty("data", entry.data);
        channel.send(player, out);
    }

    // ------------------------------------------------------------------ Abbauen

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!plugin.locks().isLockable(block)) {
            return;
        }
        synchronized (entries) {
            // bei Doppeltruhen heißt der gemerkte Stand nach dem Abbau einer Hälfte nicht mehr dasselbe: weg damit
            for (Block half : plugin.locks().halves(block)) {
                if (entries.remove(key(half.getWorld().getName(), half.getX(), half.getY(), half.getZ())) != null) {
                    dirty = true;
                }
            }
        }
    }

    /** Anzahl gemerkter Stände (für Tests). */
    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    // ------------------------------------------------------------------ Speichern

    private void trim() {
        int max = maxEntries();
        while (entries.size() > max) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath())) {
            Map<String, Entry> loaded = GSON.fromJson(reader, FILE_TYPE);
            if (loaded != null) {
                synchronized (entries) {
                    entries.putAll(loaded);
                }
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "sync.json konnte nicht gelesen werden", e);
        }
    }

    private void saveIfDirty() {
        Map<String, Entry> copy;
        synchronized (entries) {
            if (!dirty) {
                return;
            }
            dirty = false;
            copy = new LinkedHashMap<>(entries);
        }
        write(copy);
    }

    /** Beim Beenden des Plugins. */
    public void saveNow() {
        Map<String, Entry> copy;
        synchronized (entries) {
            copy = new LinkedHashMap<>(entries);
            dirty = false;
        }
        write(copy);
    }

    private void write(Map<String, Entry> copy) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            File temp = new File(dir, "sync.json.tmp");
            try (Writer writer = Files.newBufferedWriter(temp.toPath())) {
                GSON.toJson(copy, FILE_TYPE, writer);
            }
            Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "sync.json konnte nicht gespeichert werden", e);
        }
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private static String key(String world, int x, int y, int z) {
        return world + "|" + x + "|" + y + "|" + z;
    }

    private static @Nullable UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonObject in, String key, int max) {
        JsonElement element = in.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value.length() > max ? value.substring(0, max) : value;
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

}
