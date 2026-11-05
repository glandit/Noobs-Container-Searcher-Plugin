package de.noobs.containersearcher;

import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

// nicht final: MockBukkit erstellt in Tests eine Unterklasse
public class NoobsContainerSearcher extends JavaPlugin {

    private TrustStore trust;
    private GroupStore groups;
    private LockManager locks;
    private Messages messages;
    private volatile Settings settings;
    private ChestService chestService;
    private GroupService groupService;
    private ModChannel modChannel;
    private ContainerSync containerSync;
    private ClientSync sync = ClientSync.NONE;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        trust = new TrustStore(new File(getDataFolder(), "trust.yml"), getLogger());
        groups = new GroupStore(new File(getDataFolder(), "groups.yml"), getLogger());
        locks = new LockManager(this, trust, groups);
        messages = new Messages();
        reload();
        chestService = new ChestService(this);
        groupService = new GroupService(this);

        // Bedienung über das UI des Client-Mods "Noob's Container Searcher"
        modChannel = new ModChannel(this);
        modChannel.register();
        sync = modChannel;
        containerSync = new ContainerSync(this);
        containerSync.register();

        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        register("truhe", new TruheCommand(this));
        register("gruppe", new GruppeCommand(this));

        getLogger().info("Truhen-Schutz aktiv für: " + getConfig().getStringList("lockable-blocks"));
    }

    @Override
    public void onDisable() {
        if (containerSync != null) {
            containerSync.saveNow();
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
        locks.setLockable(LockManager.parseMaterials(getConfig().getStringList("lockable-blocks"), getLogger()));
        locks.setGroupMembersCanBreak(settings.groupMembersCanBreak());
        trust.load();
        groups.load();
    }

    public TrustStore trust() {
        return trust;
    }

    public GroupStore groups() {
        return groups;
    }

    public LockManager locks() {
        return locks;
    }

    public Messages messages() {
        return messages;
    }

    public Settings settings() {
        return settings;
    }

    public ChestService chestService() {
        return chestService;
    }

    public GroupService groupService() {
        return groupService;
    }

    public ModChannel modChannel() {
        return modChannel;
    }

    public ContainerSync containerSync() {
        return containerSync;
    }

    public ClientSync sync() {
        return sync;
    }
}
