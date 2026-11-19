package dev.nexus.bedwars.config;

import dev.nexus.bedwars.BedwarsPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single source of truth for config.yml.
 *
 * <p>Reads are served from an immutable, flattened snapshot ({@code path → value}) that is
 * swapped atomically – safe to call from any thread (async DB code, region threads, PAPI).
 * Writes (GUI, commands, hot-reload) funnel through {@link #set}/{@link #reload}, update the
 * snapshot immediately ("live sync") and persist to disk asynchronously with comments preserved.
 */
public final class ConfigService {

    private final BedwarsPlugin plugin;
    private final Path file;
    private final Object lock = new Object();
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private final List<Runnable> reloadListeners = new CopyOnWriteArrayList<>();

    private YamlConfiguration yaml = new YamlConfiguration();
    private volatile Map<String, Object> snapshot = Map.of();

    public ConfigService(BedwarsPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("config.yml");
    }

    public void load() {
        plugin.saveDefaultConfig();
        if (!Files.exists(plugin.getDataFolder().toPath().resolve("arenas.yml"))) {
            plugin.saveResource("arenas.yml", false);
        }
        reload();
    }

    /** Re-reads the file from disk, merges defaults from the jar and notifies listeners. */
    public void reload() {
        synchronized (lock) {
            YamlConfiguration disk = YamlConfiguration.loadConfiguration(file.toFile());
            Map<String, Object> merged = new LinkedHashMap<>();
            try (Reader reader = new InputStreamReader(Objects.requireNonNull(plugin.getResource("config.yml")), StandardCharsets.UTF_8)) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                flatten(defaults, merged);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not read bundled config.yml: " + e.getMessage());
            }
            flatten(disk, merged);
            this.yaml = disk;
            this.snapshot = Collections.unmodifiableMap(merged);
        }
        reloadListeners.forEach(Runnable::run);
    }

    private static void flatten(ConfigurationSection section, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : section.getValues(true).entrySet()) {
            if (!(e.getValue() instanceof ConfigurationSection)) out.put(e.getKey(), e.getValue());
        }
    }

    public void onReload(Runnable listener) {
        reloadListeners.add(listener);
    }

    // ── typed reads (thread-safe) ────────────────────────────────────────────

    public String getString(String path, String def) {
        Object v = snapshot.get(path);
        return v == null ? def : String.valueOf(v);
    }

    public int getInt(String path, int def) {
        return snapshot.get(path) instanceof Number n ? n.intValue() : def;
    }

    public double getDouble(String path, double def) {
        return snapshot.get(path) instanceof Number n ? n.doubleValue() : def;
    }

    public boolean getBoolean(String path, boolean def) {
        return snapshot.get(path) instanceof Boolean b ? b : def;
    }

    public List<?> list(String path) {
        return snapshot.get(path) instanceof List<?> l ? l : List.of();
    }

    public List<String> stringList(String path) {
        List<String> out = new ArrayList<>();
        for (Object o : list(path)) out.add(String.valueOf(o));
        return out;
    }

    public boolean has(String path) {
        return snapshot.containsKey(path);
    }

    /** All leaf keys – used for command auto-completion of /bedwars config set. */
    public Set<String> keys() {
        return snapshot.keySet();
    }

    public Object raw(String path) {
        return snapshot.get(path);
    }

    // ── writes ───────────────────────────────────────────────────────────────

    /** Live update from GUI / command: snapshot is updated immediately, file is written async. */
    public void set(String path, Object value) {
        synchronized (lock) {
            yaml.set(path, value);
            Map<String, Object> copy = new LinkedHashMap<>(snapshot);
            copy.put(path, value);
            snapshot = Collections.unmodifiableMap(copy);
        }
        queueSave();
    }

    private void queueSave() {
        if (!saveQueued.compareAndSet(false, true)) return;
        plugin.scheduler().asyncLater(() -> {
            saveQueued.set(false);
            String data;
            synchronized (lock) {
                data = yaml.saveToString();
            }
            try {
                if (plugin.watcher() != null) plugin.watcher().suppress("config.yml", 1500);
                Path tmp = file.resolveSibling("config.yml.tmp");
                Files.writeString(tmp, data, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save config.yml: " + e.getMessage());
            }
        }, 500, TimeUnit.MILLISECONDS);
    }
}
