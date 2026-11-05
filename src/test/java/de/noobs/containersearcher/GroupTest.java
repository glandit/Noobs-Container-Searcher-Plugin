package de.noobs.containersearcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gruppen über echte Befehle. Wer was in der Suche sieht, prüft {@link SyncTest}. */
class GroupTest {

    private ServerMock server;
    private NoobsContainerSearcher plugin;
    private GroupStore groups;
    private PlayerMock anna;
    private PlayerMock ben;
    private PlayerMock carl;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(NoobsContainerSearcher.class);
        groups = plugin.groups();
        anna = server.addPlayer("Anna");
        ben = server.addPlayer("Ben");
        carl = server.addPlayer("Carl");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Anna gründet "Noobs", lädt Ben ein, Ben nimmt an. */
    private void annaAndBenFormGroup() {
        anna.performCommand("gruppe erstellen Noobs");
        anna.performCommand("gruppe einladen Ben");
        ben.performCommand("gruppe annehmen");
    }

    @Test
    void inviteAndAcceptJoinsTheGroup() {
        annaAndBenFormGroup();
        assertTrue(groups.sameGroup(anna.getUniqueId(), ben.getUniqueId()));
        assertFalse(groups.sameGroup(anna.getUniqueId(), carl.getUniqueId()));
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
    void leavingOrKickRemovesMembership() {
        annaAndBenFormGroup();
        ben.performCommand("gruppe verlassen");
        assertFalse(groups.sameGroup(anna.getUniqueId(), ben.getUniqueId()));

        anna.performCommand("gruppe einladen Ben");
        ben.performCommand("gruppe annehmen");
        assertTrue(groups.sameGroup(anna.getUniqueId(), ben.getUniqueId()));

        anna.performCommand("gruppe entfernen Ben");
        assertFalse(groups.sameGroup(anna.getUniqueId(), ben.getUniqueId()));
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

    /** Alte Einladung darf nicht in eine neue, gleichnamige Gruppe eines anderen Spielers führen. */
    @Test
    void staleInviteDoesNotJoinRecreatedGroup() {
        anna.performCommand("gruppe erstellen Noobs");
        anna.performCommand("gruppe einladen Ben");
        anna.performCommand("gruppe verlassen"); // letztes Mitglied → Gruppe gelöscht
        carl.performCommand("gruppe erstellen Noobs");

        ben.performCommand("gruppe annehmen");
        assertNull(groups.groupOf(ben.getUniqueId()));
        assertFalse(groups.sameGroup(carl.getUniqueId(), ben.getUniqueId()));
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
