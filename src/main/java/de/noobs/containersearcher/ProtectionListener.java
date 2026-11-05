package de.noobs.containersearcher;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.UUID;

import static de.noobs.containersearcher.Messages.nameOf;
import static de.noobs.containersearcher.Messages.placeholder;

public final class ProtectionListener implements Listener {

    private final NoobsContainerSearcher plugin;
    private final LockManager locks;
    private final Messages messages;

    public ProtectionListener(NoobsContainerSearcher plugin) {
        this.plugin = plugin;
        this.locks = plugin.locks();
        this.messages = plugin.messages();
    }

    // ------------------------------------------------------------------ Platzieren

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (block.getType() == Material.HOPPER) {
            // Merkt sich, wer den Trichter gesetzt hat (darf dann aus eigenen Truhen ziehen).
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (block.getType() == Material.HOPPER) {
                    locks.setPlacer(block, uuid);
                }
            });
            return;
        }

        if (!locks.isLockable(block)) {
            return;
        }

        // Neue Hälfte einer Doppeltruhe? Dann darf nur der Besitzer der anderen Hälfte erweitern.
        Block other = locks.otherHalf(block);
        ContainerLock otherLock = other == null ? null : locks.readLock(other);
        if (otherLock != null && !locks.canBreak(player, otherLock)) {
            event.setCancelled(true);
            messages.send(player, "denied-extend", placeholder("owner", nameOf(otherLock.owner())));
            return;
        }

        ContainerLock newLock = otherLock != null
                ? otherLock
                : plugin.settings().autoLock() ? ContainerLock.ownedBy(uuid) : null;
        if (newLock == null) {
            return;
        }

        // Einen Tick später schreiben, wenn der Block (und ggf. die Nachbarhälfte) fertig gesetzt ist.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!locks.isLockable(block)) {
                return;
            }
            locks.setLock(block, newLock);
            messages.actionBar(player, otherLock != null ? "extended" : "auto-locked");
        });
    }

    // ------------------------------------------------------------------ Öffnen

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!locks.isLockable(block)) {
            return;
        }
        Player player = event.getPlayer();

        // Schleichen mit Item in der Hand öffnet in Vanilla nichts (z.B. Block anbauen).
        PlayerInventory inventory = player.getInventory();
        if (player.isSneaking()
                && (!inventory.getItemInMainHand().isEmpty() || !inventory.getItemInOffHand().isEmpty())) {
            return;
        }

        ContainerLock lock = locks.getLock(block);
        if (lock == null) {
            return;
        }
        boolean mainHand = event.getHand() == EquipmentSlot.HAND;

        if (locks.hasAccess(player.getUniqueId(), block)) {
            return;
        }
        if (player.hasPermission(LockManager.ADMIN_PERMISSION)) {
            if (mainHand && plugin.settings().notifyAdminAccess()) {
                messages.actionBar(player, "admin-access", placeholder("owner", nameOf(lock.owner())));
            }
            return;
        }

        event.setCancelled(true);
        if (mainHand) {
            messages.send(player, "denied", placeholder("owner", nameOf(lock.owner())));
        }
    }

    /** Fallback, falls ein Inventar auf anderem Weg geöffnet wird (z.B. durch andere Plugins). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Block block = locks.containerBlock(event.getInventory());
        if (block != null && !locks.canOpen(player, block)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ Abbauen

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!locks.isLockable(block)) {
            return;
        }
        List<ContainerLock> all = locks.locks(block);
        if (all.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        for (ContainerLock lock : all) {
            if (!locks.canBreak(player, lock)) {
                event.setCancelled(true);
                messages.send(player, "denied-break", placeholder("owner", nameOf(lock.owner())));
                return;
            }
        }
        if (locks.otherHalf(block) == null) {
            messages.actionBar(player, "break-unlocked");
        }
    }

    // ------------------------------------------------------------------ Explosionen & Mobs

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (plugin.settings().protectFromExplosions()) {
            event.blockList().removeIf(locks::isLocked);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (plugin.settings().protectFromExplosions()) {
            event.blockList().removeIf(locks::isLocked);
        }
    }

    /** z.B. Wither, die Blöcke zerstören. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player) && locks.isLocked(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ Trichter

    /**
     * Trichter dürfen nur aus gesicherten Truhen ziehen, wenn sie von jemandem gesetzt wurden,
     * der selbst Zugriff hat. Trichterloren werden immer blockiert.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (!plugin.settings().protectFromHoppers()) {
            return;
        }
        Block source = locks.containerBlock(event.getSource());
        if (source == null) {
            return;
        }
        UUID placer = event.getDestination().getHolder(false) instanceof Hopper hopper
                ? locks.getPlacer(hopper)
                : null; // Trichterlore o.ä.
        if (!locks.mayExtract(source, placer)) {
            event.setCancelled(true);
        }
    }
}
