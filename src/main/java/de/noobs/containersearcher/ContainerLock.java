package de.noobs.containersearcher;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Schutz einer Truhe: ein Besitzer und beliebig viele Spieler, mit denen sie geteilt ist.
 */
public record ContainerLock(UUID owner, Set<UUID> shared) {

    public ContainerLock {
        shared = Collections.unmodifiableSet(new LinkedHashSet<>(shared));
    }

    public static ContainerLock ownedBy(UUID owner) {
        return new ContainerLock(owner, Set.of());
    }

    public boolean isOwner(UUID player) {
        return owner.equals(player);
    }

    public ContainerLock withShared(UUID player) {
        Set<UUID> copy = new LinkedHashSet<>(shared);
        copy.add(player);
        return new ContainerLock(owner, copy);
    }

    public ContainerLock withoutShared(UUID player) {
        Set<UUID> copy = new LinkedHashSet<>(shared);
        copy.remove(player);
        return new ContainerLock(owner, copy);
    }
}
