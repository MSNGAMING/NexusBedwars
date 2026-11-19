package dev.nexus.bedwars.db;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lock-free, thread-safe in-memory player profile.
 *
 * <p>Gameplay code (any region thread) mutates it with {@link #add}; the async flusher drains
 * the pending deltas. Nothing here ever blocks.
 */
public final class PlayerProfile {

    private static final Stat[] STATS = Stat.values();

    private final UUID uuid;
    private volatile String name;
    private final AtomicLongArray values = new AtomicLongArray(STATS.length);
    private final AtomicLongArray pending = new AtomicLongArray(STATS.length);
    private final AtomicInteger dirtyAbsolute = new AtomicInteger();

    public PlayerProfile(UUID uuid, String name, long startElo) {
        this.uuid = uuid;
        this.name = name;
        values.set(Stat.ELO.ordinal(), startElo);
    }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public void name(String name) { this.name = name; }

    public long get(Stat stat) { return values.get(stat.ordinal()); }

    /** Increments an additive stat (queues a delta) – or adds to an absolute one (marks it dirty). */
    public long add(Stat stat, long delta) {
        long result = values.addAndGet(stat.ordinal(), delta);
        if (stat.additive()) pending.addAndGet(stat.ordinal(), delta);
        else dirtyAbsolute.accumulateAndGet(1 << stat.ordinal(), (a, b) -> a | b);
        return result;
    }

    public void set(Stat stat, long value) {
        values.set(stat.ordinal(), value);
        if (stat.additive()) throw new IllegalArgumentException("Use add() for additive stats");
        dirtyAbsolute.accumulateAndGet(1 << stat.ordinal(), (a, b) -> a | b);
    }

    /** Win/loss bookkeeping incl. streak handling. */
    public void recordResult(boolean won) {
        add(Stat.GAMES_PLAYED, 1);
        if (won) {
            add(Stat.WINS, 1);
            long streak = add(Stat.WINSTREAK, 1);
            if (streak > get(Stat.BEST_WINSTREAK)) set(Stat.BEST_WINSTREAK, streak);
        } else {
            add(Stat.LOSSES, 1);
            set(Stat.WINSTREAK, 0);
        }
    }

    // ── persistence hooks (DatabaseManager) ──────────────────────────────────

    /** Loads a persisted value without producing a pending delta. */
    void load(Stat stat, long value) { values.set(stat.ordinal(), value); }

    long[] drainPending() {
        long[] out = new long[STATS.length];
        for (int i = 0; i < STATS.length; i++) out[i] = pending.getAndSet(i, 0);
        return out;
    }

    int drainDirtyMask() { return dirtyAbsolute.getAndSet(0); }

    void restore(long[] deltas, int mask) {
        for (int i = 0; i < deltas.length; i++) if (deltas[i] != 0) pending.addAndGet(i, deltas[i]);
        dirtyAbsolute.accumulateAndGet(mask, (a, b) -> a | b);
    }

    public boolean isDirty() {
        if (dirtyAbsolute.get() != 0) return true;
        for (int i = 0; i < STATS.length; i++) if (pending.get(i) != 0) return true;
        return false;
    }

    /** Applies an update that was already persisted by another server (Redis Pub/Sub). */
    public void applyRemote(Stat stat, long value) {
        if (stat.additive()) values.addAndGet(stat.ordinal(), value);
        else values.set(stat.ordinal(), value);
    }

    // ── derived ratios (exposed through PlaceholderAPI) ──────────────────────

    public double ratio(Stat numerator, Stat denominator) {
        long d = get(denominator);
        double n = get(numerator);
        return d == 0 ? n : n / d;
    }

    public Map<Stat, Long> snapshot() {
        Map<Stat, Long> map = new EnumMap<>(Stat.class);
        for (Stat s : STATS) map.put(s, get(s));
        return map;
    }
}
