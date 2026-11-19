package dev.nexus.bedwars.listener;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.arena.ArenaState;
import dev.nexus.bedwars.arena.Game;
import dev.nexus.bedwars.gui.Msg;
import dev.nexus.bedwars.model.ArenaDefinition;
import dev.nexus.bedwars.model.ArenaDefinition.TeamDefinition;
import dev.nexus.bedwars.model.BlockPos;
import dev.nexus.bedwars.model.TeamColor;
import dev.nexus.bedwars.shop.ShopMenu;
import dev.nexus.bedwars.shop.UpgradeMenu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-game rules. Each handler runs on the thread that owns the event's location/entity, and only
 * calls thread-safe {@link Game} APIs (or schedules via {@code Scheduler}).
 */
public final class GameListener implements Listener {

    private final BedwarsPlugin plugin;
    private final Map<UUID, Long> fireballCooldown = new ConcurrentHashMap<>();

    public GameListener(BedwarsPlugin plugin) {
        this.plugin = plugin;
    }

    private Game gameOf(Player p) { return plugin.arenas().gameOf(p.getUniqueId()); }

    private Game gameAt(Location loc) {
        if (loc.getWorld() == null) return null;
        for (Game g : plugin.arenas().games()) {
            if (g.state() == ArenaState.RUNNING && g.definition().contains(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ())) return g;
        }
        return null;
    }

    private boolean isInsideAnyArena(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        String w = loc.getWorld().getName();
        for (ArenaDefinition def : plugin.arenas().definitions()) {
            if (def.contains(w, loc.getX(), loc.getY(), loc.getZ())) return true;
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Building & bed rules
    // ═════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Game g = gameOf(p);
        if (g == null) return;
        Block b = e.getBlockPlaced();
        if (g.state() != ArenaState.RUNNING || !g.isPlaying(p.getUniqueId())
                || !g.definition().contains(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()) || nearTeamSpawn(g.definition(), b)) {
            e.setCancelled(true);
            return;
        }
        if (b.getType() == Material.TNT) { // instantly primed TNT
            b.setType(Material.AIR, false);
            Location at = b.getLocation().add(0.5, 0, 0.5);
            int fuse = plugin.config().getInt("game.tnt.fuse-ticks", 52);
            b.getWorld().spawn(at, TNTPrimed.class, t -> {
                t.setFuseTicks(fuse);
                t.setSource(p);
            });
            return;
        }
        g.rollback().recordPlaced(b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Player p = e.getPlayer();
        Game g = gameOf(p);
        Block b = e.getBlockClicked().getRelative(e.getBlockFace());
        if (g == null) {
            if (isInsideAnyArena(b.getLocation())) e.setCancelled(true);
            return;
        }
        if (g.state() != ArenaState.RUNNING || !g.isPlaying(p.getUniqueId())
                || !g.definition().contains(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()) || nearTeamSpawn(g.definition(), b)) {
            e.setCancelled(true);
            return;
        }
        g.rollback().recordPlaced(b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Player p = e.getPlayer();
        Game g = gameOf(p);
        Block b = e.getBlockClicked();
        if (g == null) {
            if (isInsideAnyArena(b.getLocation())) e.setCancelled(true);
            return;
        }
        if (g.state() != ArenaState.RUNNING || !g.isPlaying(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (g.rollback().isPlayerPlaced(b)) {
            g.rollback().forgetPlaced(b);
        } else {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent e) {
        Block to = e.getToBlock();
        if (Tag.BEDS.isTagged(to.getType())) {
            e.setCancelled(true);
            return;
        }
        Game g = gameAt(to.getLocation());
        if (g != null && !g.definition().contains(to.getWorld().getName(), to.getX(), to.getY(), to.getZ())) {
            e.setCancelled(true);
        }
    }

    private boolean nearTeamSpawn(ArenaDefinition def, Block b) {
        double r = plugin.config().getDouble("game.spawn-protection-radius", 4);
        for (TeamDefinition t : def.teams().values()) {
            if (t.spawn() != null && t.spawn().distanceSquared(b.getX() + 0.5, b.getY(), b.getZ() + 0.5) <= r * r) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Game g = gameOf(p);
        if (g == null) return;
        UUID id = p.getUniqueId();
        Block b = e.getBlock();
        if (g.state() != ArenaState.RUNNING || !g.isPlaying(id)) {
            e.setCancelled(true);
            return;
        }
        if (Tag.BEDS.isTagged(b.getType())) {
            e.setCancelled(true);
            TeamColor owner = g.bedOwnerAt(b);
            if (owner == null) return;
            if (owner == g.teamOf(id)) {
                plugin.messages().send(p, "bed-own");
                return;
            }
            if (g.bedBroken(owner, p)) g.removeBedBlocks(b);
            return;
        }
        if (g.rollback().isPlayerPlaced(b)) g.rollback().forgetPlaced(b);
        else e.setCancelled(true); // the map itself is indestructible
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TNT / fireball jumping
    // ═════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        Location loc = e.getLocation();
        Game g = gameAt(loc);
        if (g == null) {
            if (isInsideAnyArena(loc)) {
                e.blockList().clear();
                e.setYield(0f);
            }
            return;
        }
        Set<Material> proof = blastProof();
        e.blockList().removeIf(b -> !g.rollback().isPlayerPlaced(b) || proof.contains(b.getType()));
        e.blockList().forEach(g.rollback()::forgetPlaced);
        e.setYield(0f);

        boolean fireball = e.getEntity() instanceof Fireball;
        String key = fireball ? "game.fireball" : "game.tnt";
        double radius = plugin.config().getDouble(key + ".radius", 4.5);
        double kb = plugin.config().getDouble(key + ".knockback", 1.6);
        double vertical = plugin.config().getDouble(key + ".vertical-boost", 0.9);
        for (Player p : loc.getNearbyPlayers(radius)) {
            if (!g.isPlaying(p.getUniqueId())) continue;
            Vector dir = p.getLocation().toVector().subtract(loc.toVector());
            double dist = dir.length();
            double falloff = Math.max(0.25, 1.0 - dist / radius);
            dir = dist < 0.001 ? new Vector(0, 1, 0) : dir.normalize();
            Vector push = new Vector(dir.getX() * kb * falloff, Math.max(dir.getY(), 0.15) * kb * 0.5 * falloff + vertical * falloff, dir.getZ() * kb * falloff);
            plugin.scheduler().entity(p, () -> p.setVelocity(push));
        }
    }

    private Set<Material> blastProof() {
        Set<Material> set = new HashSet<>();
        for (String s : plugin.config().stringList("game.blast-proof-materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) set.add(m);
        }
        return set;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFireballUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Game g = gameOf(p);
        if (g == null) return;
        if (g.isSpectator(p.getUniqueId()) || g.state() != ArenaState.RUNNING) {
            e.setCancelled(true);
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND || e.getItem() == null || e.getItem().getType() != Material.FIRE_CHARGE) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        long cooldown = plugin.config().getInt("game.fireball.cooldown-ticks", 10) * 50L;
        if (now - fireballCooldown.getOrDefault(p.getUniqueId(), 0L) < cooldown) return;
        fireballCooldown.put(p.getUniqueId(), now);

        ItemStack inHand = p.getInventory().getItemInMainHand();
        inHand.setAmount(inHand.getAmount() - 1);
        double speed = plugin.config().getDouble("game.fireball.speed", 1.2);
        float power = (float) plugin.config().getDouble("game.fireball.power", 2.4);
        Fireball fb = p.launchProjectile(Fireball.class, p.getEyeLocation().getDirection().multiply(speed));
        fb.setIsIncendiary(false);
        fb.setYield(power);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBridgeEggLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof Egg egg && egg.getShooter() instanceof Player p) {
            Game g = gameOf(p);
            if (g == null || g.state() != ArenaState.RUNNING || !g.isPlaying(p.getUniqueId())) return;
            TeamColor color = g.teamOf(p.getUniqueId());
            if (color == null) return;
            Material wool = color.wool();

            plugin.scheduler().entityTimer(egg, task -> {
                if (!egg.isValid() || egg.isDead()) {
                    task.cancel();
                    return;
                }
                Location loc = egg.getLocation();
                if (loc.getWorld() == null || !g.definition().contains(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ())) {
                    task.cancel();
                    return;
                }
                Vector vel = egg.getVelocity().clone().setY(0);
                double len = vel.length();
                Vector perp = len > 0.05 ? new Vector(-vel.getZ() / len, 0, vel.getX() / len) : new Vector(1, 0, 0);

                Location center = loc.clone().subtract(0, 2, 0);
                for (int offset = -1; offset <= 1; offset++) {
                    Location target = center.clone().add(perp.clone().multiply(offset));
                    Block b = target.getBlock();
                    if (b.getType() == Material.AIR
                            && g.definition().contains(target.getWorld().getName(), target.getX(), target.getY(), target.getZ())
                            && !nearTeamSpawn(g.definition(), b)) {
                        b.setType(wool, false);
                        g.rollback().recordPlaced(b);
                    }
                }
            }, 1, 1);
        }
    }

    @EventHandler
    public void onEggThrow(PlayerEggThrowEvent e) {
        if (gameOf(e.getPlayer()) != null) {
            e.setHatching(false);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Damage / death pipeline
    // ═════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Game g = gameOf(victim);
        if (g == null) return;
        UUID vid = victim.getUniqueId();
        DamageCause cause = e.getCause();

        if (g.state() != ArenaState.RUNNING || g.isSpectator(vid)) {
            e.setCancelled(true);
            return;
        }

        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            attacker = resolveAttacker(byEntity.getDamager());
            if (attacker != null && !attacker.equals(victim)) {
                if (g.isSpectator(attacker.getUniqueId()) || g.teamOf(attacker.getUniqueId()) == g.teamOf(vid)) {
                    e.setCancelled(true);
                    return;
                }
                g.recordDamage(vid, attacker.getUniqueId());
            }
        }

        if (cause == DamageCause.VOID) {
            e.setCancelled(true);
            g.handleLethal(victim, g.lastAttacker(vid).map(Bukkit::getPlayer).orElse(null));
            return;
        }
        if (g.isImmune(vid)) {
            e.setCancelled(true);
            return;
        }

        if (cause == DamageCause.ENTITY_EXPLOSION || cause == DamageCause.BLOCK_EXPLOSION) {
            boolean fireball = e instanceof EntityDamageByEntityEvent b && b.getDamager() instanceof Fireball;
            double dmg = plugin.config().getDouble(fireball ? "game.fireball.damage" : "game.tnt.damage", 4.0);
            if (attacker != null && attacker.equals(victim)) dmg *= 0.5; // rocket-jumping hurts less
            e.setDamage(dmg);
        }

        if (victim.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            Player killer = attacker != null && !attacker.equals(victim) ? attacker : g.lastAttacker(vid).map(Bukkit::getPlayer).orElse(null);
            g.handleLethal(victim, killer);
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player pl) return pl;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player pl) return pl;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player pl) return pl;
        return null;
    }

    /** Spectators that drift out of the arena or into the void are pulled back. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Game g = gameOf(p);
        if (g == null || !g.isSpectator(p.getUniqueId()) || g.definition().spectatorSpawn() == null) return;
        Location to = e.getTo();
        if (to == null) return;

        boolean outOfBounds = to.getY() < -64;
        if (!outOfBounds && g.definition().hasRegion()) {
            BlockPos min = g.definition().min();
            BlockPos max = g.definition().max();
            if (to.getX() < min.x() - 12 || to.getX() > max.x() + 13
                    || to.getZ() < min.z() - 12 || to.getZ() > max.z() + 13
                    || to.getY() > max.y() + 30) {
                outOfBounds = true;
            }
        }
        if (outOfBounds) {
            plugin.scheduler().teleport(p, g.definition().spectatorSpawn().toLocation());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NPC interaction
    // ═════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpc(PlayerInteractEntityEvent e) {
        String tag = e.getRightClicked().getPersistentDataContainer().get(plugin.npcKey(), PersistentDataType.STRING);
        if (tag == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Game g = gameOf(p);
        if (g == null || !g.isPlaying(p.getUniqueId())) return;
        TeamColor color = g.teamOf(p.getUniqueId());
        if (tag.startsWith("shop")) new ShopMenu(plugin, g, p, null).open(p);
        else if (g.team(color) != null) new UpgradeMenu(plugin, g, g.team(color)).open(p);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Misc protections
    // ═════════════════════════════════════════════════════════════════════════

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p) {
            Game g = gameOf(p);
            if (g != null && g.state() != ArenaState.RUNNING) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Game g = gameOf(e.getPlayer());
        if (g != null && (g.state() != ArenaState.RUNNING || g.isSpectator(e.getPlayer().getUniqueId()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) {
            Game g = gameOf(p);
            if (g != null && (g.state() != ArenaState.RUNNING || g.isSpectator(p.getUniqueId()))) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && e.getSlotType() == InventoryType.SlotType.ARMOR && gameOf(p) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        if (gameOf(e.getPlayer()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeathFallback(PlayerDeathEvent e) {
        // Lethal damage is intercepted in onDamage; this only fires for edge cases (commands, plugins).
        Game g = gameOf(e.getEntity());
        if (g != null) {
            e.setKeepInventory(false);
            e.getDrops().clear();
            e.deathMessage(null);
        }
    }
}
