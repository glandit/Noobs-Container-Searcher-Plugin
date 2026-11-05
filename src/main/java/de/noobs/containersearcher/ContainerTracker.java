package de.noobs.containersearcher;

import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.UUID;

/**
 * Vergibt Besitzer (Platzieren, erstes Öffnen) und meldet dem {@link ContainerIndex}, wo sich Inhalte
 * geändert haben könnten. Blockiert nie etwas: alle Handler laufen auf MONITOR.
 */
public final class ContainerTracker implements Listener {

    private final NoobsContainerSearcher plugin;
    private final Ownership ownership;
    private final ContainerIndex index;

    public ContainerTracker(NoobsContainerSearcher plugin) {
        this.plugin = plugin;
        this.ownership = plugin.ownership();
        this.index = plugin.index();
    }

    // ------------------------------------------------------------------ Besitzer

    /** Neue Kiste gehört dem, der sie setzt. Wird eine Doppeltruhe daraus, übernimmt sie den Besitzer der anderen Hälfte. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!ownership.isTracked(block)) {
            return;
        }
        Block other = ownership.otherHalf(block);
        UUID owner = other == null ? null : ownership.readOwner(other);
        ownership.setOwner(block, owner != null ? owner : event.getPlayer().getUniqueId());
        index.markDirtyWithHalves(block);
    }

    /** Alte Kisten ohne Besitzer gehören dem, der sie als Erster öffnet. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        Block block = ownership.containerBlock(event.getInventory());
        if (block == null) {
            return;
        }
        if (plugin.settings().claimOnFirstOpen()
                && event.getPlayer() instanceof Player player
                && player.getGameMode() != GameMode.SPECTATOR
                && ownership.owner(block) == null) {
            ownership.setOwner(block, player.getUniqueId());
            plugin.messages().send(player, "claimed");
        }
        index.markDirtyWithHalves(block);
    }

    // ------------------------------------------------------------------ Inhalt geändert

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        touch(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        touch(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        touch(event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        touch(event.getSource());
        touch(event.getDestination());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        touch(event.getInventory());
    }

    private void touch(Inventory inventory) {
        index.markDirtyWithHalves(ownership.containerBlock(inventory));
    }

    // ------------------------------------------------------------------ Block weg

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        gone(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        gone(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        gone(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        gone(List.of(event.getBlock()));
    }

    private void gone(List<Block> blocks) {
        for (Block block : blocks) {
            if (ownership.isTracked(block)) {
                index.markDirtyWithHalves(block);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        index.onChunkLoad(event.getChunk());
    }
}
