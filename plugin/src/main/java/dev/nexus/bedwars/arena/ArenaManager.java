package dev.nexus.bedwars.arena;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.model.ArenaDefinition;
import dev.nexus.bedwars.model.ValidationReport;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Owns arena definitions (persistent model) and games (runtime instances).
 * A {@link Game} exists only while its arena is enabled.
 */
public final class ArenaManager {

    private final BedwarsPlugin plugin;
    private final Path file;
    private final Object ioLock = new Object();
    private final AtomicBoolean saveQueued = new AtomicBoolean();
    private final Map<String, ArenaDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, Game> games = new ConcurrentHashMap<>();

    public ArenaManager(BedwarsPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("arenas.yml");
    }

    // ── lookup ───────────────────────────────────────────────────────────────

    public Collection<ArenaDefinition> definitions() { return definitions.values(); }

    public Optional<ArenaDefinition> definition(String id) {
        return Optional.ofNullable(id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<String> ids() { return definitions.keySet().stream().sorted().toList(); }

    public Collection<Game> games() { return games.values(); }

    public Game game(String id) { return games.get(id.toLowerCase(Locale.ROOT)); }

    public Game gameOf(UUID player) {
        for (Game g : games.values()) if (g.hasPlayer(player)) return g;
        return null;
    }

    /** Best joinable arena: prefers the fullest lobby that is still open. */
    public Game bestJoinable() {
        return games.values().stream()
                .filter(g -> g.state().joinable() && g.playerCount() < g.definition().maxPlayers())
                .max(Comparator.comparingInt(Game::playerCount)).orElse(null);
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    /** Loads arenas.yml (called on the global thread during startup and on hot-reload). */
    public synchronized void loadAll() {
        List<ArenaDefinition> loaded = ArenaRepository.fromYaml(YamlConfiguration.loadConfiguration(file.toFile()));
        Set<String> seen = new HashSet<>();
        for (ArenaDefinition fresh : loaded) {
            seen.add(fresh.id());
            Game running = games.get(fresh.id());
            if (running != null && running.playerCount() > 0) continue; // never disturb a live round
            if (running != null) { running.shutdown(); games.remove(fresh.id()); }
            definitions.put(fresh.id(), fresh);
            if (fresh.enabled()) activate(fresh);
        }
        for (String id : new ArrayList<>(definitions.keySet())) {
            Game g = games.get(id);
            if (!seen.contains(id) && (g == null || g.playerCount() == 0)) {
                if (g != null) { g.shutdown(); games.remove(id); }
                definitions.remove(id);
            }
        }
        plugin.getLogger().info("Loaded " + definitions.size() + " arena(s), " + games.size() + " enabled.");
    }

    /** Hot-reload hook for arenas.yml edits made on disk. */
    public void reloadFromDisk() {
        plugin.scheduler().global(this::loadAll);
    }

    public ArenaDefinition create(String id, int teamCount, int teamSize) {
        ArenaDefinition def = new ArenaDefinition(id.toLowerCase(Locale.ROOT));
        def.teamCount(teamCount);
        def.teamSize(teamSize);
        def.minPlayers(Math.min(2, def.maxPlayers()));
        definitions.put(def.id(), def);
        markDirty();
        return def;
    }

    public boolean delete(String id) {
        ArenaDefinition def = definitions.remove(id.toLowerCase(Locale.ROOT));
        if (def == null) return false;
        Game g = games.remove(def.id());
        if (g != null) g.shutdown();
        markDirty();
        return true;
    }

    /** Validates and enables an arena. The report tells the caller why it failed. */
    public ValidationReport enable(ArenaDefinition def) {
        ValidationReport report = ValidationReport.of(def);
        if (!report.valid()) return report;
        def.enabled(true);
        if (!games.containsKey(def.id())) activate(def);
        markDirty();
        return report;
    }

    public void disable(ArenaDefinition def) {
        def.enabled(false);
        Game g = games.remove(def.id());
        if (g != null) g.shutdown();
        markDirty();
    }

    private void activate(ArenaDefinition def) {
        games.computeIfAbsent(def.id(), id -> new Game(plugin, def));
    }

    /** Called by every GUI/command edit: persist and make sure an enabled arena is still valid. */
    public void onDefinitionChanged(ArenaDefinition def) {
        markDirty();
        if (def.enabled() && !ValidationReport.of(def).valid()) {
            Game g = games.get(def.id());
            if (g == null || g.playerCount() == 0) {
                plugin.getLogger().warning("Arena '" + def.id() + "' no longer passes validation – disabling.");
                disable(def);
            }
        }
    }

    public void shutdown() {
        games.values().forEach(Game::shutdown);
        games.clear();
        saveNow();
    }

    // ── persistence (debounced, atomic, async) ───────────────────────────────

    public void markDirty() {
        if (!saveQueued.compareAndSet(false, true)) return;
        plugin.scheduler().asyncLater(this::saveNow, 750, TimeUnit.MILLISECONDS);
    }

    public void saveNow() {
        saveQueued.set(false);
        String yaml = ArenaRepository.toYaml(definitions.values());
        synchronized (ioLock) {
            try {
                plugin.watcher().suppress("arenas.yml", 1500);
                Path tmp = file.resolveSibling("arenas.yml.tmp");
                Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save arenas.yml", e);
            }
        }
    }
}
