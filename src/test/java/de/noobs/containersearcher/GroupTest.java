package de.noobs.containersearcher;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupTest {

    private ServerMock server;
    private NoobsContainerSearcher plugin;
    private GroupStore groups;
    private WorldMock world;
    private PlayerMock anna;
    private PlayerMock ben;
    private PlayerMock carl;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(NoobsContainerSearcher.class);
        groups = plugin.groups();
        world = server.addSimpleWorld("world");
        anna = server.addPlayer("Anna");
        ben = server.addPlayer("Ben");
        carl = server.addPlayer("Carl");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Block chestOf(PlayerMock owner, int x) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(Material.CHEST);
        plugin.locks().setLock(block, ContainerLock.ownedBy(owner.getUniqueId()));
        return block;
    }

    private boolean opens(PlayerMock player, Block block) {
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event.useInteractedBlock() != Event.Result.DENY;
    }

    private boolean breaks(PlayerMock player, Block block) {
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        server.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    /** Anna gründet "Noobs", lädt Ben ein, Ben nimmt an – alles über echte Befehle. */
    private void annaAndBenFormGroup() {
        anna.performCommand("gruppe erstellen Noobs");
        anna.performCommand("gruppe einladen Ben");
        ben.performCommand("gruppe annehmen");
    }

    @Test
    void groupMembersOpenAllChestsOfEachOther() {
        Block annasChest = chestOf(anna, 0);
        Block bensChest = chestOf(ben, 2);
        assertFalse(opens(ben, annasChest));

        annaAndBenFormGroup();

        assertTrue(groups.sameGroup(anna.getUniqueId(), ben.getUniqueId()));
        assertTrue(opens(ben, annasChest), "Ben öffnet Annas Truhe");
        assertTrue(opens(anna, bensChest), "Anna öffnet Bens Truhe");
        assertFalse(opens(carl, annasChest), "Carl ist nicht in der Gruppe");
    }

    @Test
    void joiningNeedsInvite() {
        anna.performCommand("gruppe erstellen Noobs");
        carl.performCommand("gruppe annehmen");
        assertNull(groups.groupOf(carl.getUniqueId()));

        // Nur der Anführer darf einladen
        annaAndBenFormGroup();
        ben.performCommand("gruppe einladen Carl");
        carl.performCommand("gruppe annehmen");
        assertNull(groups.groupOf(carl.getUniqueId()));
    }

    @Test
    void declinedInviteDoesNotJoin() {
        anna.performCommand("gruppe erstellen Noobs");
        anna.performCommand("gruppe einladen Ben");
        ben.performCommand("gruppe ablehnen");
        ben.performCommand("gruppe annehmen");
        assertNull(groups.groupOf(ben.getUniqueId()));
    }

    @Test
    void leavingOrKickRemovesAccess() {
        Block annasChest = chestOf(anna, 0);
        annaAndBenFormGroup();
        assertTrue(opens(ben, annasChest));

        ben.performCommand("gruppe verlassen");
        assertFalse(opens(ben, annasChest));

        anna.performCommand("gruppe einladen Ben");
        ben.performCommand("gruppe annehmen");
        assertTrue(opens(ben, annasChest));

        anna.performCommand("gruppe entfernen Ben");
        assertFalse(opens(ben, annasChest));
    }

    @Test
    void leaderLeavingPassesLeadership() {
        annaAndBenFormGroup();
        anna.performCommand("gruppe verlassen");

        GroupStore.Group group = groups.groupOf(ben.getUniqueId());
        assertNotNull(group);
        assertTrue(group.isLeader(ben.getUniqueId()));
    }

    @Test
    void disbandRemovesEveryone() {
        annaAndBenFormGroup();
        ben.performCommand("gruppe auflösen"); // nicht Anführer → nichts passiert
        assertNotNull(groups.groupOf(ben.getUniqueId()));

        anna.performCommand("gruppe auflösen");
        assertNull(groups.groupOf(anna.getUniqueId()));
        assertNull(groups.groupOf(ben.getUniqueId()));
        assertNull(groups.byName("noobs"));
    }

    @Test
    void cannotBeInTwoGroups() {
        anna.performCommand("gruppe erstellen Noobs");
        carl.performCommand("gruppe erstellen Pros");
        anna.performCommand("gruppe einladen Carl");
        carl.performCommand("gruppe annehmen");
        assertEquals("Pros", groups.groupOf(carl.getUniqueId()).name());
    }

    @Test
    void membersCanBreakUnlessDisabled() {
        Block annasChest = chestOf(anna, 0);
        annaAndBenFormGroup();
        assertTrue(breaks(ben, annasChest));

        plugin.locks().setGroupMembersCanBreak(false);
        assertFalse(breaks(ben, annasChest));
        assertTrue(opens(ben, annasChest), "Öffnen geht weiterhin");
    }

    @Test
    void groupsSurviveRestart() {
        annaAndBenFormGroup();

        GroupStore reloaded = new GroupStore(new File(plugin.getDataFolder(), "groups.yml"), plugin.getLogger());
        reloaded.load();
        GroupStore.Group group = reloaded.groupOf(ben.getUniqueId());
        assertNotNull(group);
        assertEquals("Noobs", group.name());
        assertTrue(group.isLeader(anna.getUniqueId()));
        assertTrue(reloaded.sameGroup(anna.getUniqueId(), ben.getUniqueId()));
    }
}
