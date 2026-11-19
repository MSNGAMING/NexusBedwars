package dev.nexus.bedwars.db;

import dev.nexus.bedwars.BedwarsPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * In-memory facade over {@link DatabaseManager}: profile cache, periodic write-back,
 * leaderboard cache and Redis fan-out. PlaceholderAPI reads exclusively from here, so
 * placeholder requests never block on I/O.
 */
public final class StatsService {

    private static final List<Stat> LEADERBOARD_STATS = List.of(
            Stat.WINS, Stat.KILLS, Stat.FINAL_KILLS, Stat.BEDS_BROKEN, Stat.WINSTREAK, Stat.ELO, Stat.GAMES_PLAYED);

    private final BedwarsPlugin plugin;
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<PlayerProfile>> inFlightLoads = new ConcurrentHashMap<>();
    private final Map<Stat, List<LeaderboardEntry>> leaderboards = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> quickBuy = new ConcurrentHashMap<>();
    private volatile RedisSync redis;

    public StatsService(BedwarsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (plugin.config().getBoolean("redis.enabled", false)) {
            redis = new RedisSync(plugin.config(), plugin.getLogger(), this::applyRemote);
            redis.start();
        }
        int flush = Math.max(10, plugin.config().getInt("database.flush-interval-seconds", 60));
        plugin.scheduler().asyncTimer(this::flushAll, flush, flush, TimeUnit.SECONDS);
        plugin.scheduler().asyncTimer(this::refreshLeaderboards, 2, 60, TimeUnit.SECONDS);
    }

    // ── profiles ─────────────────────────────────────────────────────────────

    public CompletableFuture<PlayerProfile> load(UUID uuid, String name) {
        CompletableFuture<PlayerProfile> future = plugin.database().loadProfile(uuid, name).thenApply(loaded -> {
            if (!inFlightLoads.containsKey(uuid)) {
                return loaded;
            }
            profiles.compute(uuid, (key, existing) -> {
                if (existing == null) return loaded;
                long[] pendingDeltas = existing.drainPending();
                int dirtyMask = existing.drainDirtyMask();
                loaded.restore(pendingDeltas, dirtyMask);
                return loaded;
            });
            inFlightLoads.remove(uuid);
            return profiles.get(uuid);
        });
        inFlightLoads.put(uuid, future);
        return future;
    }

    public void unload(UUID uuid) {
        inFlightLoads.remove(uuid);
        PlayerProfile p = profiles.remove(uuid);
        quickBuy.remove(uuid);
        if (p != null) flush(p);
    }

    /** Never null: returns a detached default profile if the real one has not finished loading. */
    public PlayerProfile profile(UUID uuid) {
        PlayerProfile p = profiles.get(uuid);
        if (p != null) return p;
        return profiles.computeIfAbsent(uuid, u -> new PlayerProfile(u, "?", plugin.config().getInt("elo.start", 1000)));
    }

    public Optional<PlayerProfile> cached(UUID uuid) {
        return Optional.ofNullable(profiles.get(uuid));
    }

    public CompletableFuture<Void> flush(PlayerProfile p) {
        return plugin.database().flush(p).handle((changes, ex) -> {
            if (ex != null) {
                plugin.getLogger().log(Level.WARNING, "Stat flush failed (will retry): " + ex.getMessage());
            } else if (redis != null && changes != null && !changes.isEmpty()) {
                redis.publish(p.uuid(), changes);
            }
            return null;
        });
    }

    public void flushAll() {
        for (PlayerProfile p : profiles.values()) if (p.isDirty()) flush(p);
    }

    public void flushBlocking() {
        plugin.database().flushBlocking(profiles.values());
    }

    private void applyRemote(UUID uuid, Map<Stat, Long> changes) {
        PlayerProfile p = profiles.get(uuid);
        if (p != null) changes.forEach(p::applyRemote);
    }

    // ── leaderboards ─────────────────────────────────────────────────────────

    public void refreshLeaderboards() {
        for (Stat stat : LEADERBOARD_STATS) {
            plugin.database().top(stat, 10).thenAccept(list -> leaderboards.put(stat, list));
        }
    }

    public List<LeaderboardEntry> leaderboard(Stat stat) {
        return leaderboards.getOrDefault(stat, List.of());
    }

    // ── quick-buy layout cache ───────────────────────────────────────────────

    public CompletableFuture<List<String>> loadQuickBuy(UUID uuid) {
        return plugin.database().loadQuickBuy(uuid).thenApply(opt -> {
            List<String> layout = opt.map(s -> s.isBlank() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(s.split(","))))
                    .orElseGet(() -> new ArrayList<>(plugin.config().stringList("shop.default-quick-buy")));
            quickBuy.put(uuid, layout);
            return layout;
        });
    }

    public List<String> quickBuy(UUID uuid) {
        return quickBuy.computeIfAbsent(uuid, u -> new ArrayList<>(plugin.config().stringList("shop.default-quick-buy")));
    }

    public void saveQuickBuy(UUID uuid) {
        List<String> layout = quickBuy(uuid);
        plugin.database().saveQuickBuy(uuid, String.join(",", layout));
    }

    public void shutdown() {
        if (redis != null) redis.close();
    }
}
