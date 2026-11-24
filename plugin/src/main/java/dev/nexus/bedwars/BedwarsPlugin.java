package dev.nexus.bedwars;

import dev.nexus.bedwars.arena.ArenaManager;
import dev.nexus.bedwars.command.BedwarsCommand;
import dev.nexus.bedwars.config.ConfigService;
import dev.nexus.bedwars.config.FileWatcher;
import dev.nexus.bedwars.config.Messages;
import dev.nexus.bedwars.db.DatabaseManager;
import dev.nexus.bedwars.db.StatsService;
import dev.nexus.bedwars.gui.ChatPrompt;
import dev.nexus.bedwars.gui.MenuListener;
import dev.nexus.bedwars.hook.BedwarsExpansion;
import dev.nexus.bedwars.hook.RewardService;
import dev.nexus.bedwars.listener.GameListener;
import dev.nexus.bedwars.listener.PlayerListener;
import dev.nexus.bedwars.platform.PlatformInfo;
import dev.nexus.bedwars.platform.Scheduler;
import dev.nexus.bedwars.setup.SetupWizard;
import dev.nexus.bedwars.shop.ShopService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Composition root. No static singletons: every service receives the plugin instance and asks it for
 * collaborators, which keeps the modules independently testable.
 *
 * <pre>
 * Startup order
 *   1. Scheduler, config (+ hot-reload watcher), messages
 *   2. Database pool + migrations   (async)
 *   3. ──► on the global thread: stats service, arenas.yml load, enable games, listeners already live
 * </pre>
 */
public final class BedwarsPlugin extends JavaPlugin {

    private final PlatformInfo platform;

    private Scheduler scheduler;
    private ConfigService config;
    private Messages messages;
    private FileWatcher watcher;
    private DatabaseManager database;
    private StatsService stats;
    private ArenaManager arenas;
    private SetupWizard wizard;
    private ChatPrompt prompts;
    private ShopService shop;
    private RewardService rewards;
    private NamespacedKey npcKey;

    public BedwarsPlugin(PlatformInfo platform) {
        this.platform = platform;
    }

    @Override
    public void onEnable() {
        scheduler = new Scheduler(this);
        config = new ConfigService(this);
        config.load();
        messages = new Messages(config);
        npcKey = new NamespacedKey(this, "npc");
        registerPermissions();

        watcher = new FileWatcher(getDataFolder().toPath(), getLogger());
        watcher.register("config.yml", config::reload);
        watcher.register("arenas.yml", () -> arenas.reloadFromDisk());
        config.onReload(() -> getLogger().info("config.yml reloaded."));

        database = new DatabaseManager(this);
        stats = new StatsService(this);
        arenas = new ArenaManager(this);
        wizard = new SetupWizard(this);
        prompts = new ChatPrompt(this);
        shop = new ShopService(this);
        rewards = new RewardService(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new GameListener(this), this);
        pm.registerEvents(wizard, this);
        pm.registerEvents(prompts, this);
        BedwarsCommand.register(this);

        if (pm.isPluginEnabled("PlaceholderAPI")) {
            new BedwarsExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered (%bedwars_*%).");
        }

        getLogger().info("Starting on " + platform.implementation() + " (regionised threading: " + platform.folia() + ")");
        database.start().whenComplete((ok, error) -> {
            if (error != null) {
                getLogger().log(Level.SEVERE, "Database initialisation failed – disabling NexusBedwars", error);
                scheduler.global(() -> Bukkit.getPluginManager().disablePlugin(this));
                return;
            }
            scheduler.global(() -> {
                stats.start();
                arenas.loadAll();
                watcher.start();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    stats.load(p.getUniqueId(), p.getName()).thenCompose(pr -> stats.loadQuickBuy(p.getUniqueId()));
                }
                getLogger().info("NexusBedwars ready.");
            });
        });
    }

    @Override
    public void onDisable() {
        if (wizard != null) wizard.shutdown();
        if (watcher != null) watcher.close();
        if (arenas != null) arenas.shutdown();
        if (stats != null) {
            stats.shutdown();
            stats.flushBlocking();      // last synchronous write – the only blocking DB call in the plugin
        }
        if (database != null) database.close();
        if (scheduler != null) scheduler.cancelAll();
    }

    private void registerPermissions() {
        PluginManager pm = getServer().getPluginManager();
        if (pm.getPermission("nexusbw.play") == null) pm.addPermission(new Permission("nexusbw.play", "Play Bedwars", PermissionDefault.TRUE));
        if (pm.getPermission("nexusbw.admin") == null) pm.addPermission(new Permission("nexusbw.admin", "Administer Bedwars", PermissionDefault.OP));
    }

    public PlatformInfo platform() { return platform; }
    public Scheduler scheduler() { return scheduler; }
    public ConfigService config() { return config; }
    public Messages messages() { return messages; }
    public FileWatcher watcher() { return watcher; }
    public DatabaseManager database() { return database; }
    public StatsService stats() { return stats; }
    public ArenaManager arenas() { return arenas; }
    public SetupWizard wizard() { return wizard; }
    public ChatPrompt prompts() { return prompts; }
    public ShopService shop() { return shop; }
    public RewardService rewards() { return rewards; }
    public NamespacedKey npcKey() { return npcKey; }
}
