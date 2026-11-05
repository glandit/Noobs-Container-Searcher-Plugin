package de.noobs.containersearcher;

import java.util.Collection;
import java.util.UUID;

/** Schickt Spielern mit dem Noob's-Container-Searcher-Mod ihren aktuellen Stand (Gruppe, Freunde, Einladung). */
public interface ClientSync {

    ClientSync NONE = players -> { };

    void refresh(Collection<UUID> players);
}
