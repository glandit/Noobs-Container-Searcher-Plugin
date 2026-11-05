package de.noobs.containersearcher;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.UUID;

import static de.noobs.containersearcher.Messages.nameOf;
import static de.noobs.containersearcher.Messages.placeholder;

/** Aktionen auf einer einzelnen Truhe – genutzt von /truhe und vom Mod-UI. */
public final class ChestService {

    private final NoobsContainerSearcher plugin;
    private final LockManager locks;
    private final Messages messages;

    public ChestService(NoobsContainerSearcher plugin) {
        this.plugin = plugin;
        this.locks = plugin.locks();
        this.messages = plugin.messages();
    }

    /** Ungesicherte Truhen darf man nur aus der Nähe sichern (wie beim Anschauen mit /truhe). */
    public boolean nearEnough(Player player, Block block) {
        if (!player.getWorld().equals(block.getWorld())) {
            return false;
        }
        double max = plugin.settings().targetDistance() + 1.5;
        return player.getEyeLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) <= max * max;
    }

    public Result lock(Player player, Block block) {
        ContainerLock lock = locks.getLock(block);
        if (lock != null) {
            return lock.isOwner(player.getUniqueId())
                    ? Result.fail("already-locked")
                    : Result.fail("locked-by-other", placeholder("owner", nameOf(lock.owner())));
        }
        if (!nearEnough(player, block)) {
            return Result.fail("too-far");
        }
        locks.setLock(block, ContainerLock.ownedBy(player.getUniqueId()));
        return Result.ok("locked");
    }

    public Result unlock(Player player, Block block) {
        ContainerLock lock = locks.getLock(block);
        if (lock == null) {
            return Result.fail("not-locked");
        }
        if (!locks.canManage(player, lock)) {
            return Result.fail("not-owner", placeholder("owner", nameOf(lock.owner())));
        }
        locks.clearLock(block);
        return Result.ok("unlocked");
    }

    public Result share(Player player, Block block, String targetName) {
        ContainerLock lock = locks.getLock(block);
        if (lock == null) {
            // Ungesicherte Truhe: beim ersten Teilen automatisch sichern.
            if (!nearEnough(player, block)) {
                return Result.fail("too-far");
            }
            lock = ContainerLock.ownedBy(player.getUniqueId());
        } else if (!locks.canManage(player, lock)) {
            return Result.fail("not-owner", placeholder("owner", nameOf(lock.owner())));
        }
        OfflinePlayer target = resolve(targetName);
        if (target == null) {
            return Result.fail("unknown-player", placeholder("player", targetName));
        }
        String name = target.getName() != null ? target.getName() : targetName;
        if (lock.isOwner(target.getUniqueId())) {
            return Result.fail("share-self");
        }
        if (lock.shared().contains(target.getUniqueId())) {
            return Result.fail("already-shared", placeholder("player", name));
        }
        locks.setLock(block, lock.withShared(target.getUniqueId()));

        Player online = target.getPlayer();
        if (online != null && !online.equals(player)) {
            messages.send(online, "shared-notify",
                    placeholder("owner", player.getName()),
                    placeholder("x", block.getX()),
                    placeholder("y", block.getY()),
                    placeholder("z", block.getZ()));
        }
        return Result.ok("shared", placeholder("player", name));
    }

    public Result unshare(Player player, Block block, String targetName) {
        ContainerLock lock = locks.getLock(block);
        if (lock == null) {
            return Result.fail("not-locked");
        }
        if (!locks.canManage(player, lock)) {
            return Result.fail("not-owner", placeholder("owner", nameOf(lock.owner())));
        }
        UUID target = findByName(lock.shared(), targetName);
        if (target == null) {
            return Result.fail("not-shared", placeholder("player", targetName));
        }
        locks.setLock(block, lock.withoutShared(target));
        return Result.ok("unshared", placeholder("player", nameOf(target)));
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    /** Spieler per Name finden, ohne den Server mit einer Mojang-Abfrage zu blockieren. */
    public static @Nullable OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    public static @Nullable UUID findByName(Collection<UUID> candidates, String name) {
        for (UUID uuid : candidates) {
            if (nameOf(uuid).equalsIgnoreCase(name) || uuid.toString().equalsIgnoreCase(name)) {
                return uuid;
            }
        }
        return null;
    }
}
