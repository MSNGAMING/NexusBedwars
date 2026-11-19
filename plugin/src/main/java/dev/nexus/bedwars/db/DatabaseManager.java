package dev.nexus.bedwars.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.config.ConfigService;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Async persistence layer: SQLite, MySQL/MariaDB and PostgreSQL behind a HikariCP pool.
 *
 * <ul>
 *   <li>Every public method returns a {@link CompletableFuture} executed on a dedicated
 *       DB thread-pool – <b>no JDBC call ever runs on a server/region thread</b>.</li>
 *   <li>Schema is portable ANSI SQL plus a versioned migration table.</li>
 *   <li>Writes are delta based ({@code col = col + ?}) so several servers can update the same
 *       profile without lost updates; the UPDATE-then-INSERT pattern avoids dialect-specific upserts.</li>
 *   <li>Column names are taken from {@link Stat} only, the table prefix is whitelist-validated.</li>
 * </ul>
 */
public final class DatabaseManager implements AutoCloseable {

    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,24}");

    private final BedwarsPlugin plugin;
    private final ConfigService cfg;
    private final Logger log;

    private HikariDataSource dataSource;
    private ExecutorService executor;
    private Dialect dialect = Dialect.SQLITE;
    private String prefix = "bw_";

    public DatabaseManager(BedwarsPlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.config();
        this.log = plugin.getLogger();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    /** Creates the pool, runs migrations. Completes exceptionally on failure. */
    public CompletableFuture<Void> start() {
        this.dialect = Dialect.parse(cfg.getString("database.type", "SQLITE"));
        String rawPrefix = cfg.getString("database.table-prefix", "bw_");
        this.prefix = SAFE_PREFIX.matcher(rawPrefix).matches() ? rawPrefix : "bw_";

        int poolSize = dialect == Dialect.SQLITE ? 1 : Math.max(2, cfg.getInt("database.pool.maximum-pool-size", 10));
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "NexusBedwars-DB-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        return CompletableFuture.runAsync(() -> {
            try {
                this.dataSource = new HikariDataSource(buildHikari(poolSize));
                try (Connection c = dataSource.getConnection()) {
                    migrate(c);
                }
                log.info("Database ready (" + dialect + ", pool=" + poolSize + ")");
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    private HikariConfig buildHikari(int poolSize) {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("NexusBedwars-Pool");
        hc.setDriverClassName(dialect.driver());
        hc.setMaximumPoolSize(poolSize);
        hc.setMinimumIdle(dialect == Dialect.SQLITE ? 1 : Math.min(poolSize, cfg.getInt("database.pool.minimum-idle", 2)));
        hc.setConnectionTimeout(cfg.getInt("database.pool.connection-timeout-ms", 10_000));
        hc.setMaxLifetime(cfg.getInt("database.pool.max-lifetime-ms", 1_740_000));

        String host = cfg.getString("database.host", "127.0.0.1");
        int port = cfg.getInt("database.port", 0);
        if (port <= 0) port = dialect.defaultPort();
        String name = cfg.getString("database.name", "bedwars");

        switch (dialect) {
            case SQLITE -> {
                File file = new File(plugin.getDataFolder(), cfg.getString("database.sqlite-file", "bedwars.db"));
                hc.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath()
                        + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000&foreign_keys=ON");
            }
            case MYSQL -> {
                hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + name
                        + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&rewriteBatchedStatements=true");
                hc.setUsername(cfg.getString("database.username", "root"));
                hc.setPassword(cfg.getString("database.password", ""));
                hc.addDataSourceProperty("cachePrepStmts", "true");
                hc.addDataSourceProperty("prepStmtCacheSize", "250");
                hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            }
            case POSTGRESQL -> {
                hc.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + name);
                hc.setUsername(cfg.getString("database.username", "postgres"));
                hc.setPassword(cfg.getString("database.password", ""));
            }
        }
        return hc;
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (dataSource != null) dataSource.close();
    }

    public Dialect dialect() { return dialect; }

    private String t(String table) { return prefix + table; }

    private <T> CompletableFuture<T> async(SqlSupplier<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = dataSource.getConnection()) {
                return work.get(c);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get(Connection c) throws SQLException;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Schema & migrations  (a statement prefixed with '?' ignores "already exists" errors)
    // ═════════════════════════════════════════════════════════════════════════

    private List<List<String>> migrations() {
        return List.of(
                // v1 – player statistics
                List.of("CREATE TABLE IF NOT EXISTS " + t("players") + " ("
                        + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
                        + "name VARCHAR(16) NOT NULL, "
                        + "wins BIGINT NOT NULL DEFAULT 0, "
                        + "losses BIGINT NOT NULL DEFAULT 0, "
                        + "kills BIGINT NOT NULL DEFAULT 0, "
                        + "deaths BIGINT NOT NULL DEFAULT 0, "
                        + "final_kills BIGINT NOT NULL DEFAULT 0, "
                        + "final_deaths BIGINT NOT NULL DEFAULT 0, "
                        + "beds_broken BIGINT NOT NULL DEFAULT 0, "
                        + "games_played BIGINT NOT NULL DEFAULT 0, "
                        + "winstreak BIGINT NOT NULL DEFAULT 0, "
                        + "best_winstreak BIGINT NOT NULL DEFAULT 0, "
                        + "elo BIGINT NOT NULL DEFAULT " + cfg.getInt("elo.start", 1000) + ", "
                        + "first_seen BIGINT NOT NULL, "
                        + "last_seen BIGINT NOT NULL)"),
                // v2 – quick-buy layouts and match history
                List.of("CREATE TABLE IF NOT EXISTS " + t("quickbuy") + " ("
                                + "uuid CHAR(36) NOT NULL PRIMARY KEY, layout VARCHAR(1024) NOT NULL)",
                        "CREATE TABLE IF NOT EXISTS " + t("matches") + " ("
                                + "id " + dialect.autoIncrementPk() + ", "
                                + "server_id VARCHAR(64) NOT NULL, "
                                + "arena VARCHAR(64) NOT NULL, "
                                + "mode VARCHAR(32) NOT NULL, "
                                + "winner_team VARCHAR(16), "
                                + "duration_s BIGINT NOT NULL, "
                                + "started_at BIGINT NOT NULL, "
                                + "ended_at BIGINT NOT NULL)"),
                // v3 – lookup indexes
                List.of("?CREATE INDEX " + t("idx_players_name") + " ON " + t("players") + " (name)",
                        "?CREATE INDEX " + t("idx_players_elo") + " ON " + t("players") + " (elo)",
                        "?CREATE INDEX " + t("idx_players_wins") + " ON " + t("players") + " (wins)")
        );
    }

    private void migrate(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + t("schema_version") + " (version INT NOT NULL)");
        }
        int current = 0;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM " + t("schema_version"))) {
            if (rs.next()) current = rs.getInt(1);
        }
        List<List<String>> steps = migrations();
        for (int version = current + 1; version <= steps.size(); version++) {
            for (String sql : steps.get(version - 1)) {
                boolean tolerant = sql.startsWith("?");
                try (Statement st = c.createStatement()) {
                    st.execute(tolerant ? sql.substring(1) : sql);
                } catch (SQLException e) {
                    if (!tolerant) throw e;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t("schema_version") + " (version) VALUES (?)")) {
                ps.setInt(1, version);
                ps.executeUpdate();
            }
            log.info("Database migrated to schema v" + version);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Player profiles
    // ═════════════════════════════════════════════════════════════════════════

    public CompletableFuture<PlayerProfile> loadProfile(UUID uuid, String name) {
        return async(c -> {
            PlayerProfile profile = readProfile(c, "uuid = ?", uuid.toString());
            if (profile != null) {
                profile.name(name);
                return profile;
            }
            long now = System.currentTimeMillis();
            long startElo = cfg.getInt("elo.start", 1000);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t("players")
                    + " (uuid, name, elo, first_seen, last_seen) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, startElo);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException duplicate) {
                // another server created the row in the meantime – read it
                PlayerProfile existing = readProfile(c, "uuid = ?", uuid.toString());
                if (existing != null) return existing;
                throw duplicate;
            }
            return new PlayerProfile(uuid, name, startElo);
        });
    }

    public CompletableFuture<Optional<PlayerProfile>> findByName(String name) {
        return async(c -> Optional.ofNullable(readProfile(c, "LOWER(name) = LOWER(?)", name)));
    }

    private PlayerProfile readProfile(Connection c, String where, String arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + t("players") + " WHERE " + where)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                PlayerProfile p = new PlayerProfile(UUID.fromString(rs.getString("uuid")), rs.getString("name"), 0);
                for (Stat s : Stat.values()) p.load(s, rs.getLong(s.column()));
                return p;
            }
        }
    }

    /**
     * Writes pending deltas of a profile. Completes with the changes that were persisted
     * (delta for additive stats, absolute value otherwise) so callers can broadcast them.
     */
    public CompletableFuture<Map<Stat, Long>> flush(PlayerProfile profile) {
        if (!profile.isDirty()) return CompletableFuture.completedFuture(Map.of());
        return async(c -> flushNow(c, profile));
    }

    /** Blocking variant for onDisable only. */
    public void flushBlocking(Collection<PlayerProfile> profiles) {
        if (dataSource == null || dataSource.isClosed()) return;
        try (Connection c = dataSource.getConnection()) {
            for (PlayerProfile p : profiles) {
                if (p.isDirty()) flushNow(c, p);
            }
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Final stat flush failed", e);
        }
    }

    private Map<Stat, Long> flushNow(Connection c, PlayerProfile p) throws SQLException {
        long[] deltas = p.drainPending();
        int dirtyMask = p.drainDirtyMask();
        Map<Stat, Long> changes = new EnumMap<>(Stat.class);
        StringBuilder set = new StringBuilder("name = ?, last_seen = ?");
        List<Long> args = new ArrayList<>();
        for (Stat s : Stat.values()) {
            if (s.additive() && deltas[s.ordinal()] != 0) {
                set.append(", ").append(s.column()).append(" = ").append(s.column()).append(" + ?");
                args.add(deltas[s.ordinal()]);
                changes.put(s, deltas[s.ordinal()]);
            } else if (!s.additive() && (dirtyMask & (1 << s.ordinal())) != 0) {
                set.append(", ").append(s.column()).append(" = ?");
                long v = p.get(s);
                args.add(v);
                changes.put(s, v);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + t("players") + " SET " + set + " WHERE uuid = ?")) {
            int i = 1;
            ps.setString(i++, p.name());
            ps.setLong(i++, System.currentTimeMillis());
            for (Long a : args) ps.setLong(i++, a);
            ps.setString(i, p.uuid().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            p.restore(deltas, dirtyMask); // never lose stats – retry on next flush
            throw e;
        }
        return changes;
    }

    public CompletableFuture<List<LeaderboardEntry>> top(Stat stat, int limit) {
        return async(c -> {
            List<LeaderboardEntry> out = new ArrayList<>();
            String sql = "SELECT uuid, name, " + stat.column() + " AS v FROM " + t("players")
                    + " ORDER BY " + stat.column() + " DESC, name ASC LIMIT ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    int rank = 1;
                    while (rs.next()) {
                        out.add(new LeaderboardEntry(rank++, UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getLong("v")));
                    }
                }
            }
            return out;
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Quick-buy layouts & match history
    // ═════════════════════════════════════════════════════════════════════════

    public CompletableFuture<Optional<String>> loadQuickBuy(UUID uuid) {
        return async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT layout FROM " + t("quickbuy") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Void> saveQuickBuy(UUID uuid, String layout) {
        return async(c -> {
            int updated;
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + t("quickbuy") + " SET layout = ? WHERE uuid = ?")) {
                ps.setString(1, layout);
                ps.setString(2, uuid.toString());
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t("quickbuy") + " (uuid, layout) VALUES (?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, layout);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public CompletableFuture<Void> recordMatch(MatchRecord m) {
        return async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t("matches")
                    + " (server_id, arena, mode, winner_team, duration_s, started_at, ended_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, m.serverId());
                ps.setString(2, m.arena());
                ps.setString(3, m.mode());
                ps.setString(4, m.winnerTeam());
                ps.setLong(5, m.durationSeconds());
                ps.setLong(6, m.startedAtEpochMs());
                ps.setLong(7, m.endedAtEpochMs());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
