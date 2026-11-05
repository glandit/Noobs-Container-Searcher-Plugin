package de.noobs.containersearcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Merkt sich den Inhalt aller Container, die jemandem gehören – auch wenn ihr Chunk gerade nicht geladen ist.
 *
 * <p>Events markieren Blöcke nur als "geändert"; {@link #process(int)} liest sie ein paar Ticks später
 * gesammelt neu ein. So kostet ein Trichter, der jede halbe Sekunde ein Item schiebt, fast nichts.
 * Jede echte Änderung geht an den {@link Listener} (den Mod-Kanal), der sie an die Spieler schickt.
 */
public final class ContainerIndex {

    /** Bekommt jede Änderung. {@code before == null}: neu, {@code after == null}: weg. */
    public interface Listener {
        Listener NONE = (before, after) -> { };

        void changed(@Nullable ContainerSnapshot before, @Nullable ContainerSnapshot after);
    }

    private record BlockRef(String world, int x, int y, int z) { }

    private record ChunkRef(String world, int x, int z) { }

    private static final Gson GSON = new GsonBuilder().create();
    private static final Type LIST_TYPE = new TypeToken<List<ContainerSnapshot>>() { }.getType();

    private final Plugin plugin;
    private final Ownership ownership;
    private final File file;
    private final Map<String, ContainerSnapshot> byKey = new HashMap<>();
    private final Map<ChunkRef, Set<String>> byChunk = new HashMap<>();
    private final Set<BlockRef> dirty = new LinkedHashSet<>();
    private Listener listener = Listener.NONE;
    private Function<ItemStack, String> encoder =
            stack -> Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    private boolean unsaved;
    private long generation;
    private long writtenGeneration;

    public ContainerIndex(Plugin plugin, Ownership ownership, File file) {
        this.plugin = plugin;
        this.ownership = ownership;
        this.file = file;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Für Tests: MockBukkit kann Items nicht serialisieren. */
    void setEncoder(Function<ItemStack, String> encoder) {
        this.encoder = encoder;
    }

    public Collection<ContainerSnapshot> all() {
        return byKey.values();
    }

    public @Nullable ContainerSnapshot get(Block block) {
        return byKey.get(key(block));
    }

    // ------------------------------------------------------------------ Änderungen

    public void markDirty(@Nullable Block block) {
        if (block != null) {
            dirty.add(new BlockRef(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        }
    }

    /** Block und (bei Doppeltruhen) die andere Hälfte. */
    public void markDirtyWithHalves(@Nullable Block block) {
        if (block != null) {
            for (Block half : ownership.halves(block)) {
                markDirty(half);
            }
        }
    }

    /** Liest bis zu {@code budget} geänderte Blöcke neu ein. Der Rest kommt beim nächsten Mal dran. */
    public void process(int budget) {
        Iterator<BlockRef> it = dirty.iterator();
        List<BlockRef> batch = new ArrayList<>();
        while (it.hasNext() && batch.size() < budget) {
            batch.add(it.next());
            it.remove();
        }
        for (BlockRef ref : batch) {
            World world = Bukkit.getWorld(ref.world());
            // nicht geladen: Inhalt kann sich dort nicht ändern, alter Stand bleibt gültig
            if (world == null || !world.isChunkLoaded(ref.x() >> 4, ref.z() >> 4)) {
                continue;
            }
            try {
                update(world.getBlockAt(ref.x(), ref.y(), ref.z()));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not read container at " + ref, e);
            }
        }
    }

    /** Alles Geänderte sofort einlesen (Tests, Abschalten). */
    public void processAll() {
        while (!dirty.isEmpty()) {
            process(Integer.MAX_VALUE);
        }
    }

    private void update(Block block) {
        String key = key(block);
        if (!ownership.isTracked(block)) {
            remove(key);
            return;
        }
        Block main = ownership.canonical(block);
        String mainKey = key(main);
        if (!mainKey.equals(key)) {
            remove(key); // war vorher eine einzelne Truhe oder die andere Hälfte lag unten
        }
        UUID owner = ownership.owner(main);
        if (owner == null || !(main.getState(false) instanceof Container container)) {
            remove(mainKey);
            return;
        }
        ItemStack[] contents = container.getInventory().getContents();
        int hash = java.util.Arrays.hashCode(contents);
        Block other = ownership.otherHalf(main);
        ContainerSnapshot.Pos second = other == null ? null : new ContainerSnapshot.Pos(other.getX(), other.getY(), other.getZ());
        String type = main.getType().getKey().toString();
        ContainerSnapshot before = byKey.get(mainKey);
        if (before != null && before.contentHash() == hash && before.owner().equals(owner)
                && before.type().equals(type) && java.util.Objects.equals(before.second(), second)) {
            return;
        }
        List<ContainerSnapshot.Item> items = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String data;
            try {
                data = encoder.apply(stack);
            } catch (RuntimeException e) {
                data = "";
            }
            items.add(new ContainerSnapshot.Item(stack.getType().getKey().toString(), stack.getAmount(), data));
        }
        World world = main.getWorld();
        ContainerSnapshot after = new ContainerSnapshot(owner, world.getName(), dimensionOf(world),
                main.getX(), main.getY(), main.getZ(), second, type, items, hash, System.currentTimeMillis());
        if (before != null && before.sameAs(after)) {
            byKey.put(mainKey, after); // nur die Prüfsumme hat sich verschoben
            return;
        }
        put(after);
        listener.changed(before, after);
    }

    private void remove(String key) {
        ContainerSnapshot before = byKey.remove(key);
        if (before == null) {
            return;
        }
        Set<String> keys = byChunk.get(chunkOf(before));
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                byChunk.remove(chunkOf(before));
            }
        }
        unsaved = true;
        listener.changed(before, null);
    }

    private void put(ContainerSnapshot snapshot) {
        byKey.put(snapshot.key(), snapshot);
        byChunk.computeIfAbsent(chunkOf(snapshot), k -> new HashSet<>()).add(snapshot.key());
        unsaved = true;
    }

    /**
     * Chunk geladen: bekannte Container darin prüfen (z.B. per WorldEdit entfernt) und Container mit Besitzer
     * aufnehmen, die noch fehlen (z.B. nach Löschen von containers.json.gz).
     */
    public void onChunkLoad(Chunk chunk) {
        String world = chunk.getWorld().getName();
        Set<String> known = byChunk.getOrDefault(new ChunkRef(world, chunk.getX(), chunk.getZ()), Set.of());
        for (String key : List.copyOf(known)) {
            ContainerSnapshot snapshot = byKey.get(key);
            if (snapshot != null) {
                dirty.add(new BlockRef(world, snapshot.x(), snapshot.y(), snapshot.z()));
            }
        }
        Collection<BlockState> states;
        try {
            states = chunk.getTileEntities(block -> ownership.isTracked(block.getType()), false);
        } catch (RuntimeException | LinkageError e) {
            return; // ältere/abweichende Server-API: dann eben nur über Events
        }
        for (BlockState state : states) {
            if (state instanceof TileState && !known.contains(ContainerSnapshot.key(world, state.getX(), state.getY(), state.getZ()))
                    && ownership.readOwner(state.getBlock()) != null) {
                dirty.add(new BlockRef(world, state.getX(), state.getY(), state.getZ()));
            }
        }
    }

    // ------------------------------------------------------------------ Speichern

    @SuppressWarnings("unchecked")
    public void load() {
        byKey.clear();
        byChunk.clear();
        if (!file.exists()) {
            return;
        }
        try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file.toPath())), StandardCharsets.UTF_8)) {
            List<ContainerSnapshot> loaded = GSON.fromJson(reader, LIST_TYPE);
            if (loaded != null) {
                for (ContainerSnapshot snapshot : loaded) {
                    if (snapshot != null && snapshot.owner() != null && snapshot.world() != null) {
                        put(snapshot);
                    }
                }
            }
            unsaved = false;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read " + file.getName() + " – starting empty", e);
        }
    }

    /** Speichert im Hintergrund, falls sich etwas geändert hat. */
    public void saveAsync() {
        if (!unsaved) {
            return;
        }
        unsaved = false;
        List<ContainerSnapshot> copy = List.copyOf(byKey.values());
        long gen = ++generation;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(copy, gen));
    }

    public void saveNow() {
        unsaved = false;
        write(List.copyOf(byKey.values()), ++generation);
    }

    private synchronized void write(List<ContainerSnapshot> snapshots, long gen) {
        if (gen <= writtenGeneration) {
            return; // ein neuerer Stand liegt schon auf der Platte
        }
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            Files.createDirectories(file.getParentFile().toPath());
            try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temp.toPath())), StandardCharsets.UTF_8)) {
                GSON.toJson(snapshots, LIST_TYPE, writer);
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            writtenGeneration = gen;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + file.getName(), e);
        }
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private static String key(Block block) {
        return ContainerSnapshot.key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static ChunkRef chunkOf(ContainerSnapshot snapshot) {
        return new ChunkRef(snapshot.world(), snapshot.x() >> 4, snapshot.z() >> 4);
    }

    /** Dimension, wie der Client sie nennt (Paper: Welt-Schlüssel, z.B. minecraft:the_nether). */
    static String dimensionOf(World world) {
        try {
            return world.getKey().toString();
        } catch (RuntimeException | LinkageError e) {
            return switch (world.getEnvironment()) {
                case NETHER -> "minecraft:the_nether";
                case THE_END -> "minecraft:the_end";
                default -> "minecraft:overworld";
            };
        }
    }
}
