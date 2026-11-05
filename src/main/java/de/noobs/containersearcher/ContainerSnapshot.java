package de.noobs.containersearcher;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Was der Server zuletzt in einem Container gesehen hat. Liegt bei Doppeltruhen unter der unteren Hälfte
 * (gleiche Regel wie im Mod), {@code second} ist dann die andere Hälfte.
 *
 * @param world       Bukkit-Weltname (zum Wiederfinden)
 * @param dimension   Dimensions-Schlüssel, wie ihn der Client kennt, z.B. {@code minecraft:overworld}
 * @param type        Block-ID, z.B. {@code minecraft:chest}
 * @param contentHash Prüfsumme der Slots – gleich = nichts neu kodieren und nichts schicken
 */
public record ContainerSnapshot(
        UUID owner,
        String world,
        String dimension,
        int x,
        int y,
        int z,
        @Nullable Pos second,
        String type,
        List<Item> items,
        int contentHash,
        long updatedAt
) {

    public ContainerSnapshot {
        items = List.copyOf(items);
    }

    /** Ein Stapel: Item-ID, Anzahl und der komplette Stapel als Base64 von {@code ItemStack#serializeAsBytes()}. */
    public record Item(String id, int count, String data) { }

    public record Pos(int x, int y, int z) { }

    public String key() {
        return key(world, x, y, z);
    }

    public static String key(String world, int x, int y, int z) {
        return world + ":" + x + "," + y + "," + z;
    }

    /** Gleicher Inhalt, Besitzer und Form – dann muss niemand etwas Neues bekommen. */
    public boolean sameAs(ContainerSnapshot other) {
        return contentHash == other.contentHash
                && owner.equals(other.owner)
                && type.equals(other.type)
                && Objects.equals(second, other.second)
                && items.equals(other.items);
    }
}
