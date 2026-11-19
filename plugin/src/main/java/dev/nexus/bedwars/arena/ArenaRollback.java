package dev.nexus.bedwars.arena;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.model.ArenaDefinition;
import dev.nexus.bedwars.model.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Item;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Arrow;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Change-journal rollback – <b>O(changed blocks), not O(arena volume)</b>.
 *
 * <p>Bedwars only permits modification of player-placed blocks (plus beds), so instead of snapshotting a
 * whole map we journal the few thousand blocks that actually change, grouped by chunk:
 * <ul>
 *   <li>placed blocks → reset to AIR (original was always air)</li>
 *   <li>removed non-placed blocks (beds, rare special cases) → restore their original {@link BlockData}</li>
 * </ul>
 * Restoration is dispatched <em>per chunk through the region scheduler</em> (so it is Folia-safe) and
 * sliced into {@code rollback.blocks-per-tick-per-chunk} batches per tick – a reset never produces a
 * tick spike, regardless of how many blocks were placed.
 */
public final class ArenaRollback {

    private final BedwarsPlugin plugin;
    private final ArenaDefinition def;
    private final Map<Long, Set<Long>> placed = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, BlockData>> removed = new ConcurrentHashMap<>();

    public ArenaRollback(BedwarsPlugin plugin, ArenaDefinition def) {
        this.plugin = plugin;
        this.def = def;
    }

    // ── journalling (called from block events on region threads) ─────────────

    public void recordPlaced(Block block) {
        placed.computeIfAbsent(chunkKey(block.getX() >> 4, block.getZ() >> 4), k -> ConcurrentHashMap.newKeySet())
                .add(pack(block.getX(), block.getY(), block.getZ()));
    }

    public boolean isPlayerPlaced(Block block) {
        Set<Long> set = placed.get(chunkKey(block.getX() >> 4, block.getZ() >> 4));
        return set != null && set.contains(pack(block.getX(), block.getY(), block.getZ()));
    }

    /** A player-placed block was broken/exploded: it is air again, nothing to restore. */
    public void forgetPlaced(Block block) {
        Set<Long> set = placed.get(chunkKey(block.getX() >> 4, block.getZ() >> 4));
        if (set != null) set.remove(pack(block.getX(), block.getY(), block.getZ()));
    }

    /** A map block (bed …) was removed: remember the original so it can be restored. */
    public void recordRemoved(Block block) {
        removed.computeIfAbsent(chunkKey(block.getX() >> 4, block.getZ() >> 4), k -> new ConcurrentHashMap<>())
                .putIfAbsent(pack(block.getX(), block.getY(), block.getZ()), block.getBlockData());
    }

    public int journalSize() {
        return placed.values().stream().mapToInt(Set::size).sum() + removed.values().stream().mapToInt(Map::size).sum();
    }

    // ── restore ──────────────────────────────────────────────────────────────

    /** Restores the arena. The future completes once every chunk has been processed. */
    public CompletableFuture<Void> restore() {
        World world = def.world() == null ? null : Bukkit.getWorld(def.world());
        Set<Long> chunks = new HashSet<>(placed.keySet());
        chunks.addAll(removed.keySet());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        if (world != null) {
            int perTick = Math.max(100, plugin.config().getInt("rollback.blocks-per-tick-per-chunk", 2500));
            for (long key : chunks) {
                Set<Long> p = placed.remove(key);
                Map<Long, BlockData> r = removed.remove(key);
                CompletableFuture<Void> done = new CompletableFuture<>();
                futures.add(done);
                int cx = (int) (key >> 32), cz = (int) key;
                plugin.scheduler().region(world, cx, cz, () -> processChunk(
                        world, cx, cz, p == null ? List.<Long>of().iterator() : p.iterator(),
                        r == null ? Map.<Long, BlockData>of() : r, perTick, done));
            }
            futures.add(clearEntities(world));
        }
        placed.clear();
        removed.clear();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private void processChunk(World world, int cx, int cz, Iterator<Long> it, Map<Long, BlockData> toRestore,
                              int perTick, CompletableFuture<Void> done) {
        try {
            int budget = perTick;
            while (it.hasNext() && budget-- > 0) {
                long packed = it.next();
                world.getBlockAt(unpackX(packed), unpackY(packed), unpackZ(packed)).setType(Material.AIR, false);
            }
            if (it.hasNext()) { // continue next tick on the same region
                plugin.scheduler().regionLater(world, cx, cz, () -> processChunk(world, cx, cz, it, toRestore, perTick, done), 1);
                return;
            }
            for (Map.Entry<Long, BlockData> e : toRestore.entrySet()) {
                long packed = e.getKey();
                world.getBlockAt(unpackX(packed), unpackY(packed), unpackZ(packed)).setBlockData(e.getValue(), false);
            }
            done.complete(null);
        } catch (RuntimeException ex) {
            done.completeExceptionally(ex);
        }
    }

    /** Removes dropped items, primed TNT, fireballs and arrows from every chunk of the arena. */
    private CompletableFuture<Void> clearEntities(World world) {
        if (!def.hasRegion()) return CompletableFuture.completedFuture(null);
        BlockPos lo = def.min(), hi = def.max();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int cx = lo.x() >> 4; cx <= hi.x() >> 4; cx++) {
            for (int cz = lo.z() >> 4; cz <= hi.z() >> 4; cz++) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                futures.add(f);
                int fx = cx, fz = cz;
                plugin.scheduler().region(world, fx, fz, () -> {
                    try {
                        if (world.isChunkLoaded(fx, fz)) {
                            Chunk chunk = world.getChunkAt(fx, fz);
                            for (Entity e : chunk.getEntities()) {
                                if (e instanceof Item || e instanceof TNTPrimed || e instanceof Fireball || e instanceof Arrow
                                        || e.getPersistentDataContainer().has(plugin.npcKey(), PersistentDataType.STRING)) {
                                    e.remove();
                                }
                            }
                        }
                        f.complete(null);
                    } catch (RuntimeException ex) {
                        f.completeExceptionally(ex);
                    }
                });
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    // ── packing helpers ──────────────────────────────────────────────────────

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** x:26 bits | z:26 bits | y:12 bits (offset 2048) – covers every legal world coordinate. */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    private static int unpackX(long p) { return (int) (p >> 38); }          // arithmetic shift keeps the sign
    private static int unpackZ(long p) { return (int) (p << 26 >> 38); }
    private static int unpackY(long p) { return (int) (p & 0xFFF) - 2048; }
}
