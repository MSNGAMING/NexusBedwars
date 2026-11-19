package dev.nexus.bedwars.listener;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.arena.Game;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.logging.Level;

/** Profile lifecycle: async load on join, leave game + flush on quit. */
public final class PlayerListener implements Listener {

    private final BedwarsPlugin plugin;

    public PlayerListener(BedwarsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();

        if (isInsideArena(p.getLocation())) {
            plugin.scheduler().entity(p, () -> {
                p.setGameMode(GameMode.SURVIVAL);
                p.setAllowFlight(false);
                p.setFlying(false);
            });
            plugin.scheduler().teleport(p, fallbackLobby());
        }

        plugin.stats().load(id, p.getName())
                .thenCompose(profile -> plugin.stats().loadQuickBuy(id))
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING, "Could not load profile of " + p.getName() + ": " + ex.getMessage());
                    return null;
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Game g = plugin.arenas().gameOf(p.getUniqueId());
        if (g != null) g.leave(p);
        plugin.stats().unload(p.getUniqueId());
    }

    private boolean isInsideArena(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        String w = loc.getWorld().getName();
        for (var def : plugin.arenas().definitions()) {
            if (def.contains(w, loc.getX(), loc.getY(), loc.getZ())) return true;
        }
        return false;
    }

    private Location fallbackLobby() {
        var cfg = plugin.config();
        World w = Bukkit.getWorld(cfg.getString("game.fallback-lobby.world", "world"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        return new Location(w, cfg.getDouble("game.fallback-lobby.x", 0.5), cfg.getDouble("game.fallback-lobby.y", 100),
                cfg.getDouble("game.fallback-lobby.z", 0.5));
    }
}
