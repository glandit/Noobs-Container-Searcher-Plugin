package de.noobs.containersearcher;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.function.Function;

/**
 * Server-Teil zum Mod "Noob's Container Searcher": Der Server merkt sich den Inhalt aller Kisten, die
 * jemandem gehören, und schickt ihn in die Mod-Suche – an den Besitzer, seine Gruppe und seine Freunde.
 * Man muss die Kisten dafür nicht öffnen. Geschützt wird nichts: alle Kisten bleiben wie in Vanilla.
 */
// nicht final: MockBukkit erstellt in Tests eine Unterklasse
public class NoobsContainerSearcher extends JavaPlugin {

    /** Alle so viele Ticks werden geänderte Kisten neu eingelesen. */
    private static final long PROCESS_TICKS = 5L;
    private static final int PROCESS_BUDGET = 64;
    private static final long SAVE_TICKS = 20L * 30L;

    private TrustStore trust;
    private GroupStore groups;
    private Ownership ownership;
    private ContainerIndex index;
    private Messages messages;
    private volatile Settings settings;
    private GroupService groupService;
    private ModChannel modChannel;
    private ClientSync sync = ClientSync.NONE;
    private Function<Player, Block> targetFinder =
            player -> player.getTargetBlockExact(settings.targetDistance());

    @Override
    public void onEnable() {
        saveDefaultConfig();

        trust = new TrustStore(new File(getDataFolder(), "trust.yml"), getLogger());
        groups = new GroupStore(new File(getDataFolder(), "groups.yml"), getLogger());
        ownership = new Ownership(this);
        index = new ContainerIndex(this, ownership, new File(getDataFolder(), "containers.json.gz"));
        messages = new Messages();
        reload();
        index.load();
        // Chunks, die schon vor dem Plugin geladen waren (Spawn, /forceload, /reload), bekommen kein ChunkLoadEvent
        // mehr – dort Container mit Besitzer (auch alte Sperren aus 1.0.0) jetzt suchen
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                index.onChunkLoad(chunk);
            }
        }
        groupService = new GroupService(this);

        // Kisten-Sync und Bedienung über das UI des Client-Mods "Noob's Container Searcher"
        modChannel = new ModChannel(this);
        modChannel.register();
        index.setListener(modChannel);
        sync = modChannel;

        getServer().getPluginManager().registerEvents(new ContainerTracker(this), this);
        getServer().getScheduler().runTaskTimer(this, () -> index.process(PROCESS_BUDGET), PROCESS_TICKS, PROCESS_TICKS);
        getServer().getScheduler().runTaskTimer(this, index::saveAsync, SAVE_TICKS, SAVE_TICKS);
        register("truhe", new TruheCommand(this));
        register("gruppe", new GruppeCommand(this));

        getLogger().info("Syncing " + index.all().size() + " known containers. Tracked blocks: "
                + getConfig().getStringList("tracked-blocks"));
    }

    @Override
    public void onDisable() {
        if (index != null) {
            index.processAll();
            index.saveNow();
        }
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    /** Lädt config.yml, trust.yml und groups.yml neu. */
    public void reload() {
        reloadConfig();
        settings = Settings.from(getConfig());
        messages.load(this, getConfig().getString("language", "auto"));
        ownership.setTracked(Ownership.parseMaterials(getConfig().getStringList("tracked-blocks"), getLogger()));
        trust.load();
        groups.load();
        // Freunde/Gruppen können sich in den Dateien geändert haben → wer was sieht, neu schicken
        sync.resync(getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList());
    }

    /** Block, den der Spieler in Reichweite anschaut, sonst null. */
    public @Nullable Block targetBlock(Player player) {
        return targetFinder.apply(player);
    }

    /** Für Tests: MockBukkit kann keine Blickrichtung auswerten. */
    void setTargetFinder(Function<Player, Block> targetFinder) {
        this.targetFinder = targetFinder;
    }

    public TrustStore trust() {
        return trust;
    }

    public GroupStore groups() {
        return groups;
    }

    public Ownership ownership() {
        return ownership;
    }

    public ContainerIndex index() {
        return index;
    }

    public Messages messages() {
        return messages;
    }

    public Settings settings() {
        return settings;
    }

    public GroupService groupService() {
        return groupService;
    }

    public ModChannel modChannel() {
        return modChannel;
    }

    public ClientSync sync() {
        return sync;
    }
}
