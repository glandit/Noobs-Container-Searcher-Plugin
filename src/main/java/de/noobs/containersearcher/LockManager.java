package de.noobs.containersearcher;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Liest und schreibt den Schutz direkt in die Block-Daten (PersistentDataContainer) der Truhe.
 * Keine Datenbank nötig: wird die Truhe abgebaut, verschwindet auch der Schutz.
 */
public final class LockManager {

    public static final String ADMIN_PERMISSION = "ncs.admin";

    private final NamespacedKey ownerKey;
    private final NamespacedKey sharedKey;
    private final NamespacedKey placerKey;
    private final TrustStore trust;
    private final GroupStore groups;
    private Set<Material> lockable = EnumSet.noneOf(Material.class);
    private boolean groupMembersCanBreak = true;

    public LockManager(Plugin plugin, TrustStore trust, GroupStore groups) {
        this.ownerKey = new NamespacedKey(plugin, "owner");
        this.sharedKey = new NamespacedKey(plugin, "shared");
        this.placerKey = new NamespacedKey(plugin, "placer");
        this.trust = trust;
        this.groups = groups;
    }

    public void setGroupMembersCanBreak(boolean groupMembersCanBreak) {
        this.groupMembersCanBreak = groupMembersCanBreak;
    }

    public void setLockable(Set<Material> materials) {
        this.lockable = materials.isEmpty() ? EnumSet.noneOf(Material.class) : EnumSet.copyOf(materials);
    }

    public boolean isLockable(Block block) {
        return lockable.contains(block.getType());
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

    /** Schutz nur dieses einen Blocks (ohne zweite Hälfte). */
    public @Nullable ContainerLock readLock(Block block) {
        if (!(block.getState(false) instanceof TileState state)) {
            return null;
        }
        PersistentDataContainer pdc = state.getPersistentDataContainer();
        String rawOwner = pdc.get(ownerKey, PersistentDataType.STRING);
        if (rawOwner == null) {
            return null;
        }
        UUID owner = parseUuid(rawOwner);
        if (owner == null) {
            return null;
        }
        Set<UUID> shared = new LinkedHashSet<>();
        String rawShared = pdc.getOrDefault(sharedKey, PersistentDataType.STRING, "");
        for (String part : rawShared.split(",")) {
            UUID uuid = parseUuid(part);
            if (uuid != null) {
                shared.add(uuid);
            }
        }
        return new ContainerLock(owner, shared);
    }

    /** Schutz der Truhe (bei Doppeltruhen von einer der beiden Hälften). */
    public @Nullable ContainerLock getLock(Block block) {
        for (Block half : halves(block)) {
            ContainerLock lock = readLock(half);
            if (lock != null) {
                return lock;
            }
        }
        return null;
    }

    /** Alle unterschiedlichen Schutz-Einträge beider Hälften. */
    public List<ContainerLock> locks(Block block) {
        List<ContainerLock> result = new ArrayList<>(2);
        for (Block half : halves(block)) {
            ContainerLock lock = readLock(half);
            if (lock != null && !result.contains(lock)) {
                result.add(lock);
            }
        }
        return result;
    }

    public boolean isLocked(Block block) {
        return isLockable(block) && getLock(block) != null;
    }

    /** Schreibt den Schutz auf beide Hälften. */
    public void setLock(Block block, ContainerLock lock) {
        String shared = lock.shared().stream().map(UUID::toString).collect(Collectors.joining(","));
        for (Block half : halves(block)) {
            BlockState snapshot = half.getState();
            if (!(snapshot instanceof TileState state)) {
                continue;
            }
            PersistentDataContainer pdc = state.getPersistentDataContainer();
            pdc.set(ownerKey, PersistentDataType.STRING, lock.owner().toString());
            if (shared.isEmpty()) {
                pdc.remove(sharedKey);
            } else {
                pdc.set(sharedKey, PersistentDataType.STRING, shared);
            }
            state.update();
        }
    }

    public void clearLock(Block block) {
        for (Block half : halves(block)) {
            if (half.getState() instanceof TileState state) {
                state.getPersistentDataContainer().remove(ownerKey);
                state.getPersistentDataContainer().remove(sharedKey);
                state.update();
            }
        }
    }

    // ------------------------------------------------------------------ Trichter-Besitzer

    public void setPlacer(Block hopper, UUID placer) {
        if (hopper.getState() instanceof TileState state) {
            state.getPersistentDataContainer().set(placerKey, PersistentDataType.STRING, placer.toString());
            state.update();
        }
    }

    public @Nullable UUID getPlacer(TileState state) {
        String raw = state.getPersistentDataContainer().get(placerKey, PersistentDataType.STRING);
        return raw == null ? null : parseUuid(raw);
    }

    /**
     * Darf ein Trichter aus dieser Truhe ziehen?
     * Ungesichert: ja. Gesichert: nur, wenn der Trichter-Setzer selbst Zugriff hat.
     */
    public boolean mayExtract(Block source, @Nullable UUID hopperPlacer) {
        List<ContainerLock> all = locks(source);
        if (all.isEmpty()) {
            return true;
        }
        if (hopperPlacer == null) {
            return false;
        }
        for (ContainerLock lock : all) {
            if (!hasAccess(hopperPlacer, lock)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ Rechte

    /** Hat der Spieler (ohne Admin-Recht) Zugriff auf diesen Schutz? */
    public boolean hasAccess(UUID player, ContainerLock lock) {
        return lock.isOwner(player)
                || lock.shared().contains(player)
                || trust.trusts(lock.owner(), player)
                || groups.sameGroup(lock.owner(), player);
    }

    /** Normaler Zugriff auf alle Hälften, ohne Admin-Recht. */
    public boolean hasAccess(UUID player, Block block) {
        for (ContainerLock lock : locks(block)) {
            if (!hasAccess(player, lock)) {
                return false;
            }
        }
        return true;
    }

    /** Darf öffnen: Besitzer, geteilt, Freund oder Admin. */
    public boolean canOpen(Player player, Block block) {
        return player.hasPermission(ADMIN_PERMISSION) || hasAccess(player.getUniqueId(), block);
    }

    /** Darf verwalten (teilen, entsperren): nur Besitzer oder Admin. */
    public boolean canManage(Player player, ContainerLock lock) {
        return lock.isOwner(player.getUniqueId()) || player.hasPermission(ADMIN_PERMISSION);
    }

    /** Darf abbauen oder zur Doppeltruhe erweitern: Besitzer, Admin und (falls erlaubt) Gruppenmitglieder. */
    public boolean canBreak(Player player, ContainerLock lock) {
        return canManage(player, lock)
                || (groupMembersCanBreak && groups.sameGroup(lock.owner(), player.getUniqueId()));
    }

    // ------------------------------------------------------------------ Inventare

    /** Truhen-Block hinter einem Inventar, falls es ein schützbarer Block ist. */
    public @Nullable Block containerBlock(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (!(holder instanceof BlockInventoryHolder) && !(holder instanceof DoubleChest)) {
            return null;
        }
        // Bei Doppeltruhen liegt die Location genau zwischen beiden Hälften
        // und wird beim Abrunden zu einer der beiden Hälften.
        Location location = inventory.getLocation();
        if (location == null) {
            return null;
        }
        Block block = location.getBlock();
        return isLockable(block) ? block : null;
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

    public static Set<Material> parseMaterials(List<String> names, java.util.logging.Logger logger) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) {
                logger.warning("Unbekannter Block in lockable-blocks: " + name
                        + " (gültig z.B. " + Arrays.asList("CHEST", "TRAPPED_CHEST", "BARREL") + ")");
                continue;
            }
            result.add(material);
        }
        return result;
    }
}
