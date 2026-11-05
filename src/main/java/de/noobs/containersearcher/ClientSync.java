package de.noobs.containersearcher;

import java.util.Collection;
import java.util.UUID;

/** Schickt Spielern mit dem Noob's-Container-Searcher-Mod ihren aktuellen Stand. */
public interface ClientSync {

    ClientSync NONE = players -> { };

    /** Gruppe, Freunde, Einladung neu schicken. */
    void refresh(Collection<UUID> players);

    /** Wie {@link #refresh}, plus alle sichtbaren Kisten neu – wenn sich geändert hat, wer was sieht. */
    default void resync(Collection<UUID> players) {
        refresh(players);
    }
}
