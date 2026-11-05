package de.noobs.containersearcher;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Wem ein Container gehört. Steht direkt in den Block-Daten (PersistentDataContainer) und verschwindet mit
 * dem Block. Besitz schützt nichts: alle können die Kiste weiter öffnen und abbauen. Der Besitzer bestimmt
 * nur, wer ihren Inhalt in der Mod-Suche sieht.
 */
public final class Ownership {

    private final NamespacedKey ownerKey;
    private Set<Material> tracked = EnumSet.noneOf(Material.class);

    public Ownership(Plugin plugin) {
        // gleicher Schlüssel wie in der alten Sperr-Version: gesperrte Kisten behalten ihren Besitzer
        this.ownerKey = new NamespacedKey(plugin, "owner");
    }

    public void setTracked(Set<Material> materials) {
        this.tracked = materials.isEmpty() ? EnumSet.noneOf(Material.class) : EnumSet.copyOf(materials);
    }

    public boolean isTracked(Block block) {
        return tracked.contains(block.getType());
    }

    public boolean isTracked(Material material) {
        return tracked.contains(material);
    }

    // ------------------------------------------------------------------ Doppeltruhen

    /** Beide Hälften einer Doppeltruhe, sonst nur der Block selbst. */
    public List<Block> halves(Block block) {
        Block other = otherHalf(block);
        return other == null ? List.of(block) : List.of(block, other);
    }

    /**
     * Andere Hälfte einer Doppeltruhe, berechnet aus Blickrichtung und Typ (LEFT/RIGHT).
     * Funktioniert auch direkt beim Platzieren, bevor der Nachbarblock aktualisiert wurde.
     */
    public @Nullable Block otherHalf(Block block) {
        if (!(block.getBlockData() instanceof Chest data) || data.getType() == Chest.Type.SINGLE) {
            return null;
        }
        BlockFace facing = data.getFacing();
        BlockFace direction = data.getType() == Chest.Type.LEFT ? clockwise(facing) : counterClockwise(facing);
        if (direction == null) {
            return null;
        }
        Block other = block.getRelative(direction);
        if (other.getType() != block.getType()
                || !(other.getBlockData() instanceof Chest otherData)
                || otherData.getFacing() != facing) {
            return null;
        }
        return other;
    }

    /**
     * Hälfte, unter der ein Container gespeichert wird: die kleinere Position (x, dann y, dann z) – dieselbe
     * Regel wie im Mod, damit gesyncte Einträge die selbst gespeicherten ersetzen statt doppelt aufzutauchen.
     */
    public Block canonical(Block block) {
        Block other = otherHalf(block);
        return other == null || compare(block, other) <= 0 ? block : other;
    }

    private static int compare(Block a, Block b) {
        int x = Integer.compare(a.getX(), b.getX());
        if (x != 0) {
            return x;
        }
        int y = Integer.compare(a.getY(), b.getY());
        return y != 0 ? y : Integer.compare(a.getZ(), b.getZ());
    }

    private static @Nullable BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> null;
        };
    }

    private static @Nullable BlockFace counterClockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ Lesen / Schreiben

    /** Besitzer nur dieses Blocks (ohne zweite Hälfte). */
    public @Nullable UUID readOwner(Block block) {
        if (!(block.getState(false) instanceof TileState state)) {
            return null;
        }
        String raw = state.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return raw == null ? null : parseUuid(raw);
    }

    /** Besitzer des Containers (bei Doppeltruhen von einer der beiden Hälften). */
    public @Nullable UUID owner(Block block) {
        for (Block half : halves(block)) {
            UUID owner = readOwner(half);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    /** Schreibt den Besitzer auf beide Hälften. */
    public void setOwner(Block block, UUID owner) {
        for (Block half : halves(block)) {
            BlockState snapshot = half.getState();
            if (snapshot instanceof TileState state) {
                state.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
                state.update();
            }
        }
    }

    public void clearOwner(Block block) {
        for (Block half : halves(block)) {
            if (half.getState() instanceof TileState state) {
                state.getPersistentDataContainer().remove(ownerKey);
                state.update();
            }
        }
    }

    // ------------------------------------------------------------------ Inventare

    /** Container-Block hinter einem Inventar, falls er gesynct wird. */
    public @Nullable Block containerBlock(@Nullable Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        // nur Kisten-Inventare; Plugin-Menüs haben keine Location, Lore-Truhen stehen nicht in einem Kisten-Block
        InventoryType type = inventory.getType();
        if (type != InventoryType.CHEST && type != InventoryType.BARREL && type != InventoryType.SHULKER_BOX) {
            return null;
        }
        InventoryHolder holder;
        try {
            holder = inventory.getHolder(false); // ohne Kopie des Block-Zustands
        } catch (RuntimeException e) {
            holder = inventory.getHolder(); // MockBukkit
        }
        Block block;
        if (holder instanceof BlockState state) {
            block = state.getBlock();
        } else if (holder instanceof DoubleChest) {
            // die Location liegt genau zwischen beiden Hälften und wird zu einer davon abgerundet
            Location location = inventory.getLocation();
            block = location == null || location.getWorld() == null ? null : location.getBlock();
        } else {
            return null; // Plugin-Menü, Lore-Truhe, ...
        }
        return block != null && isTracked(block) ? block : null;
    }

    private static @Nullable UUID parseUuid(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Material-Namen aus config.yml; SHULKER_BOXES steht für alle 17 Shulkerkisten. */
    public static Set<Material> parseMaterials(List<String> names, java.util.logging.Logger logger) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String name : names) {
            if ("SHULKER_BOXES".equalsIgnoreCase(name.trim())) {
                result.addAll(Tag.SHULKER_BOXES.getValues());
                continue;
            }
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) {
                logger.warning("Unknown block in tracked-blocks: " + name
                        + " (valid e.g. " + Arrays.asList("CHEST", "TRAPPED_CHEST", "BARREL", "SHULKER_BOXES") + ")");
                continue;
            }
            result.add(material);
        }
        return result;
    }
}
