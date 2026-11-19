package dev.nexus.bedwars.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ONE scheduling facade for Paper, Purpur and Folia.
 *
 * <p>Paper (since 1.20) ships the Folia scheduler API on every server. On regular
 * Paper the global/region/entity schedulers simply execute on the main thread, on
 * Folia they run on the owning region thread. That means we never branch on
 * "is this Folia?" and we never touch the legacy {@code BukkitScheduler}.
 *
 * <h3>Thread-ownership rules enforced by this codebase</h3>
 * <ul>
 *   <li><b>global</b>   – plugin state machines, countdowns, world-independent work</li>
 *   <li><b>region</b>   – block / chunk / world-entity access at a location (generators, rollback)</li>
 *   <li><b>entity</b>   – anything on a Player/Entity: inventory, gamemode, health, GUI open</li>
 *   <li><b>async</b>    – JDBC, Redis, file I/O. Never touches Bukkit state.</li>
 * </ul>
 */
public final class Scheduler {

    private final Plugin plugin;

    public Scheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    // ── global ───────────────────────────────────────────────────────────────

    public ScheduledTask global(Runnable task) {
        return Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
    }

    public ScheduledTask globalLater(Runnable task, long delayTicks) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), Math.max(1, delayTicks));
    }

    public ScheduledTask globalTimer(Consumer<ScheduledTask> task, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task, Math.max(1, delayTicks), Math.max(1, periodTicks));
    }

    // ── region (location / chunk owned) ──────────────────────────────────────

    public ScheduledTask region(Location location, Runnable task) {
        return Bukkit.getRegionScheduler().run(plugin, location, t -> task.run());
    }

    public ScheduledTask region(World world, int chunkX, int chunkZ, Runnable task) {
        return Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, t -> task.run());
    }

    public ScheduledTask regionLater(World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
        return Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, t -> task.run(), Math.max(1, delayTicks));
    }

    public ScheduledTask regionTimer(Location location, Consumer<ScheduledTask> task, long delayTicks, long periodTicks) {
        return Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, task, Math.max(1, delayTicks), Math.max(1, periodTicks));
    }

    // ── entity ───────────────────────────────────────────────────────────────

    /** Runs on the entity's thread. If the entity is removed before execution the task is dropped. */
    public ScheduledTask entity(Entity entity, Runnable task) {
        return entity.getScheduler().run(plugin, t -> task.run(), null);
    }

    public ScheduledTask entityLater(Entity entity, Runnable task, long delayTicks) {
        return entity.getScheduler().runDelayed(plugin, t -> task.run(), null, Math.max(1, delayTicks));
    }

    public ScheduledTask entityTimer(Entity entity, Consumer<ScheduledTask> task, long delayTicks, long periodTicks) {
        return entity.getScheduler().runAtFixedRate(plugin, task, null, Math.max(1, delayTicks), Math.max(1, periodTicks));
    }

    /** Runs inline when the caller already owns the entity, otherwise hops threads. */
    public void ensureEntity(Entity entity, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            entity(entity, task);
        }
    }

    // ── async ────────────────────────────────────────────────────────────────

    public ScheduledTask async(Runnable task) {
        return Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public ScheduledTask asyncLater(Runnable task, long delay, TimeUnit unit) {
        return Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delay, unit);
    }

    public ScheduledTask asyncTimer(Runnable task, long delay, long period, TimeUnit unit) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> task.run(), delay, period, unit);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Cross-region safe teleport (never use Entity#teleport on Folia). */
    public CompletableFuture<Boolean> teleport(Entity entity, Location target) {
        if (target == null || target.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }
        return entity.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    public void cancelAll() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }
}
