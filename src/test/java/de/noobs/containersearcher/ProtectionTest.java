package de.noobs.containersearcher;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionTest {

    private ServerMock server;
    private NoobsContainerSearcher plugin;
    private LockManager locks;
    private WorldMock world;
    private PlayerMock owner;
    private PlayerMock stranger;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(NoobsContainerSearcher.class);
        locks = plugin.locks();
        world = server.addSimpleWorld("world");
        owner = server.addPlayer("Owner");
        stranger = server.addPlayer("Fremder");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Block chest(int x) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(Material.CHEST);
        return block;
    }

    private boolean opens(PlayerMock player, Block block) {
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event.useInteractedBlock() != Event.Result.DENY;
    }

    @Test
    void lockRoundTripsThroughBlockData() {
        Block block = chest(0);
        assertNull(locks.getLock(block));

        locks.setLock(block, ContainerLock.ownedBy(owner.getUniqueId()).withShared(stranger.getUniqueId()));

        ContainerLock lock = locks.getLock(block);
        assertNotNull(lock);
        assertEquals(owner.getUniqueId(), lock.owner());
        assertTrue(lock.shared().contains(stranger.getUniqueId()));

        locks.clearLock(block);
        assertNull(locks.getLock(block));
    }

    @Test
    void strangerCannotOpenUntilShared() {
        Block block = chest(0);
        locks.setLock(block, ContainerLock.ownedBy(owner.getUniqueId()));

        assertTrue(opens(owner, block));
        assertFalse(opens(stranger, block));

        locks.setLock(block, locks.getLock(block).withShared(stranger.getUniqueId()));
        assertTrue(opens(stranger, block));

        locks.setLock(block, locks.getLock(block).withoutShared(stranger.getUniqueId()));
        assertFalse(opens(stranger, block));
    }

    @Test
    void trustedFriendOpensAllChests() {
        Block a = chest(0);
        Block b = chest(5);
        locks.setLock(a, ContainerLock.ownedBy(owner.getUniqueId()));
        locks.setLock(b, ContainerLock.ownedBy(owner.getUniqueId()));
        assertFalse(opens(stranger, a));

        plugin.trust().add(owner.getUniqueId(), stranger.getUniqueId());
        assertTrue(opens(stranger, a));
        assertTrue(opens(stranger, b));

        plugin.trust().remove(owner.getUniqueId(), stranger.getUniqueId());
        assertFalse(opens(stranger, b));
    }

    @Test
    void adminBypassesLock() {
        Block block = chest(0);
        locks.setLock(block, ContainerLock.ownedBy(owner.getUniqueId()));
        stranger.setOp(true);
        assertTrue(opens(stranger, block));
    }

    @Test
    void onlyOwnerCanBreak() {
        Block block = chest(0);
        locks.setLock(block, ContainerLock.ownedBy(owner.getUniqueId()).withShared(stranger.getUniqueId()));

        BlockBreakEvent byStranger = new BlockBreakEvent(block, stranger);
        server.getPluginManager().callEvent(byStranger);
        assertTrue(byStranger.isCancelled(), "Geteilte Spieler dürfen nicht abbauen");

        BlockBreakEvent byOwner = new BlockBreakEvent(block, owner);
        server.getPluginManager().callEvent(byOwner);
        assertFalse(byOwner.isCancelled());
    }

    @Test
    void placingAutoLocksForPlacer() {
        Block block = chest(0);
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), world.getBlockAt(0, 63, 0),
                new ItemStack(Material.CHEST), owner, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        server.getScheduler().performOneTick();

        ContainerLock lock = locks.getLock(block);
        assertNotNull(lock);
        assertEquals(owner.getUniqueId(), lock.owner());
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
    void doubleChestHalvesShareLock() {
        // Linke Hälfte schaut nach Norden → rechte Hälfte liegt östlich (x + 1).
        Block left = chestHalf(0, Chest.Type.LEFT);
        Block right = chestHalf(1, Chest.Type.RIGHT);

        assertEquals(right, locks.otherHalf(left));
        assertEquals(left, locks.otherHalf(right));

        locks.setLock(right, ContainerLock.ownedBy(owner.getUniqueId()));
        assertNotNull(locks.readLock(left), "setLock schreibt auf beide Hälften");
        assertFalse(opens(stranger, left));
        assertFalse(opens(stranger, right));
        assertTrue(opens(owner, left));
    }

    @Test
    void strangerCannotExtendForeignChest() {
        // Owner hat eine gesicherte Einzeltruhe bei x=1 ...
        Block right = chestHalf(1, Chest.Type.RIGHT);
        locks.setLock(right, ContainerLock.ownedBy(owner.getUniqueId()));
        // ... Fremder setzt links daneben eine Truhe, die sich verbinden würde.
        Block left = chestHalf(0, Chest.Type.LEFT);
        assertNull(locks.readLock(left));

        BlockPlaceEvent place = new BlockPlaceEvent(left, left.getState(), world.getBlockAt(0, 63, 0),
                new ItemStack(Material.CHEST), stranger, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);
        assertTrue(place.isCancelled());
    }

    @Test
    void ownerExtensionCopiesShares() {
        Block right = chestHalf(1, Chest.Type.RIGHT);
        locks.setLock(right, ContainerLock.ownedBy(owner.getUniqueId()).withShared(stranger.getUniqueId()));
        Block left = chestHalf(0, Chest.Type.LEFT);

        BlockPlaceEvent place = new BlockPlaceEvent(left, left.getState(), world.getBlockAt(0, 63, 0),
                new ItemStack(Material.CHEST), owner, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);
        server.getScheduler().performOneTick();

        assertFalse(place.isCancelled());
        ContainerLock lock = locks.readLock(left);
        assertNotNull(lock);
        assertTrue(lock.shared().contains(stranger.getUniqueId()));
    }

    @Test
    void foreignHopperCannotStealButOwnHopperCan() {
        Block block = chest(0);
        assertTrue(locks.mayExtract(block, null), "Ungesicherte Truhe: Trichter ok");

        locks.setLock(block, ContainerLock.ownedBy(owner.getUniqueId()));
        assertFalse(locks.mayExtract(block, null), "Trichterlore / Trichter ohne Besitzer");
        assertFalse(locks.mayExtract(block, stranger.getUniqueId()), "Trichter eines Fremden");
        assertTrue(locks.mayExtract(block, owner.getUniqueId()), "Eigener Trichter");

        // Gesetzter Trichter merkt sich seinen Besitzer
        Block hopper = world.getBlockAt(0, 63, 0);
        hopper.setType(Material.HOPPER);
        locks.setPlacer(hopper, owner.getUniqueId());
        assertEquals(owner.getUniqueId(), locks.getPlacer((org.bukkit.block.TileState) hopper.getState()));
    }

    @Test
    void unlockedChestsStayOpen() {
        Block block = chest(0);
        assertTrue(opens(stranger, block));
    }
}
