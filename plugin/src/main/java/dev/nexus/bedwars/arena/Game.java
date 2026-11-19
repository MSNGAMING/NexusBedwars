package dev.nexus.bedwars.arena;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.config.Messages;
import dev.nexus.bedwars.db.MatchRecord;
import dev.nexus.bedwars.db.PlayerProfile;
import dev.nexus.bedwars.db.Stat;
import dev.nexus.bedwars.gui.Msg;
import dev.nexus.bedwars.model.ArenaDefinition;
import dev.nexus.bedwars.model.ArenaDefinition.GeneratorDefinition;
import dev.nexus.bedwars.model.ArenaDefinition.TeamDefinition;
import dev.nexus.bedwars.model.GeneratorType;
import dev.nexus.bedwars.model.TeamColor;
import dev.nexus.bedwars.platform.Compat;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One live Bedwars round for an enabled arena.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li><b>Global region</b>: a 1 Hz loop drives the state machine (countdowns, event timeline).</li>
 *   <li><b>Region schedulers</b>: one timer per generator (runs on the thread that owns the generator's chunk),
 *       one per team base for traps, block/NPC operations.</li>
 *   <li><b>Entity schedulers</b>: every inventory / gamemode / health / potion change on a player.</li>
 *   <li>Shared collections are concurrent; transitions go through the lock-free {@link ArenaStateMachine}.</li>
 * </ul>
 */
public final class Game {

    private record Snapshot(ItemStack[] contents, GameMode mode, Location location) {}

    private record Damage(UUID attacker, long at) {}

    private static final Set<Material> RESOURCES = EnumSet.of(
            Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD);

    private final BedwarsPlugin plugin;
    private final ArenaDefinition def;
    private final ArenaStateMachine fsm = new ArenaStateMachine(ArenaState.WAITING);
    private final ReentrantLock lock = new ReentrantLock();
    private final ArenaRollback rollback;

    private final Set<UUID> players = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();   // eliminated
    private final Set<UUID> limbo = ConcurrentHashMap.newKeySet();        // waiting to respawn
    private final Map<UUID, TeamColor> assignment = new ConcurrentHashMap<>();
    private final Map<TeamColor, TeamState> teams = new ConcurrentHashMap<>();
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Damage> lastDamage = new ConcurrentHashMap<>();
    private final Map<UUID, Material> armorTier = new ConcurrentHashMap<>();
    private final Map<UUID, Long> immuneUntil = new ConcurrentHashMap<>();
    private final Map<GeneratorType, Integer> generatorTiers = new ConcurrentHashMap<>();
    private final List<ScheduledTask> tasks = new CopyOnWriteArrayList<>();
    private final List<UUID> npcs = new CopyOnWriteArrayList<>();
    private final List<Location> npcLocations = new CopyOnWriteArrayList<>();
    private final ScheduledTask loop;

    private volatile int countdown;
    private volatile long elapsed;
    private volatile long startedAt;

    public Game(BedwarsPlugin plugin, ArenaDefinition def) {
        this.plugin = plugin;
        this.def = def;
        this.rollback = new ArenaRollback(plugin, def);
        this.loop = plugin.scheduler().globalTimer(t -> tick(), 20, 20);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Accessors
    // ═════════════════════════════════════════════════════════════════════════

    public ArenaDefinition definition() { return def; }
    public ArenaState state() { return fsm.state(); }
    public ArenaRollback rollback() { return rollback; }
    public int playerCount() { return players.size(); }
    public boolean hasPlayer(UUID id) { return players.contains(id); }
    public boolean isSpectator(UUID id) { return spectators.contains(id) || limbo.contains(id); }
    public boolean isPlaying(UUID id) { return players.contains(id) && assignment.containsKey(id) && !isSpectator(id); }
    public TeamColor teamOf(UUID id) { return assignment.get(id); }
    public TeamState team(TeamColor c) { return teams.get(c); }
    public Collection<TeamState> teams() { return teams.values(); }
    public long elapsedSeconds() { return elapsed; }
    public int countdownSeconds() { return countdown; }
    public Set<UUID> playerIds() { return Collections.unmodifiableSet(players); }
    public boolean isImmune(UUID id) { return isSpectator(id) || immuneUntil.getOrDefault(id, 0L) > System.currentTimeMillis(); }
    public int generatorTier(GeneratorType t) { return generatorTiers.getOrDefault(t, 1); }

    public Optional<UUID> lastAttacker(UUID victim) {
        Damage d = lastDamage.get(victim);
        return d != null && System.currentTimeMillis() - d.at() < 12_000 ? Optional.of(d.attacker()) : Optional.empty();
    }

    public void recordDamage(UUID victim, UUID attacker) {
        lastDamage.put(victim, new Damage(attacker, System.currentTimeMillis()));
    }

    public void setArmorTier(UUID id, Material boots) { armorTier.put(id, boots); }

    // ═════════════════════════════════════════════════════════════════════════
    //  Join / leave
    // ═════════════════════════════════════════════════════════════════════════

    public boolean join(Player p) {
        UUID id = p.getUniqueId();
        lock.lock();
        try {
            if (!fsm.state().joinable() || players.size() >= def.maxPlayers() || !players.add(id)) return false;
        } finally {
            lock.unlock();
        }
        Location lobby = def.waitingLobby() == null ? null : def.waitingLobby().toLocation();
        plugin.scheduler().entity(p, () -> {
            ItemStack[] copy = p.getInventory().getContents().clone();
            for (int i = 0; i < copy.length; i++) if (copy[i] != null) copy[i] = copy[i].clone();
            snapshots.put(id, new Snapshot(copy, p.getGameMode(), p.getLocation()));
            resetPlayer(p, GameMode.ADVENTURE);
        });
        plugin.scheduler().teleport(p, lobby);
        broadcast("player-join", Messages.text("player", p.getName()), Messages.text("count", players.size()),
                Messages.text("max", def.maxPlayers()));
        checkStart();
        return true;
    }

    public void leave(Player p) {
        UUID id = p.getUniqueId();
        if (!players.remove(id)) return;
        boolean wasPlaying = fsm.state() == ArenaState.RUNNING && assignment.containsKey(id) && !spectators.contains(id);
        TeamColor color = assignment.get(id);
        spectators.remove(id);
        limbo.remove(id);
        restore(p);
        if (wasPlaying && color != null) {
            TeamState ts = teams.get(color);
            if (ts != null) ts.members().remove(id);
            PlayerProfile profile = stats(id);
            profile.recordResult(false);
            double opponents = assignment.entrySet().stream()
                    .filter(o -> o.getValue() != color && !o.getKey().equals(id) && players.contains(o.getKey()))
                    .mapToLong(o -> stats(o.getKey()).get(Stat.ELO)).average().orElse(profile.get(Stat.ELO));
            int k = plugin.config().getInt("elo.k-factor", 24);
            profile.add(Stat.ELO, EloCalculator.delta(profile.get(Stat.ELO), opponents, false, k));
            plugin.scheduler().async(() -> plugin.stats().flush(profile));
            broadcast("death", Messages.comp("victim", coloredName(p, color)));
            if (ts != null && ts.eliminated()) {
                broadcast("team-eliminated", Messages.comp("team", teamName(color)));
            }
            checkWin();
        } else if (fsm.state().joinable()) {
            broadcast("player-leave", Messages.text("player", p.getName()), Messages.text("count", players.size()),
                    Messages.text("max", def.maxPlayers()));
        }
    }

    private void restore(Player p) {
        UUID id = p.getUniqueId();
        Snapshot s = snapshots.remove(id);
        Location dest = s != null ? s.location() : fallbackLobby();
        plugin.scheduler().ensureEntity(p, () -> {
            p.getInventory().clear();
            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            p.setAllowFlight(false);
            p.setFlying(false);
            if (s != null) {
                p.getInventory().setContents(s.contents());
                p.setGameMode(s.mode());
            } else {
                p.setGameMode(GameMode.SURVIVAL);
            }
        });
        plugin.scheduler().teleport(p, dest);
    }

    private Location fallbackLobby() {
        var cfg = plugin.config();
        World w = Bukkit.getWorld(cfg.getString("game.fallback-lobby.world", "world"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        return new Location(w, cfg.getDouble("game.fallback-lobby.x", 0.5), cfg.getDouble("game.fallback-lobby.y", 100),
                cfg.getDouble("game.fallback-lobby.z", 0.5));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  State machine driver (global thread, 1 Hz)
    // ═════════════════════════════════════════════════════════════════════════

    private void checkStart() {
        int count = players.size();
        if (fsm.state() == ArenaState.WAITING && count >= def.minPlayers()
                && fsm.transition(ArenaState.WAITING, ArenaState.STARTING)) {
            countdown = plugin.config().getInt("game.lobby-countdown", 20);
        }
        if (fsm.state() == ArenaState.STARTING && count >= def.maxPlayers()) {
            countdown = Math.min(countdown, plugin.config().getInt("game.full-lobby-countdown", 5));
        }
    }

    private void tick() {
        try {
            switch (fsm.state()) {
                case STARTING -> tickStarting();
                case RUNNING -> tickRunning();
                case ENDING -> {
                    if (--countdown <= 0) reset();
                }
                default -> { }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Arena tick failed: " + def.id(), ex);
        }
    }

    private void tickStarting() {
        if (players.size() < def.minPlayers()) {
            if (fsm.transition(ArenaState.STARTING, ArenaState.WAITING)) broadcast("countdown-cancelled");
            return;
        }
        int c = countdown--;
        if (c <= 0) {
            start();
            return;
        }
        if (c <= 5 || c % 10 == 0) {
            broadcast("countdown", Messages.text("seconds", c));
            for (UUID id : players) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                p.showTitle(Title.title(Component.text(c, c <= 3 ? net.kyori.adventure.text.format.NamedTextColor.RED
                        : net.kyori.adventure.text.format.NamedTextColor.YELLOW), Component.empty()));
                Msg.sound(p, "block.note_block.hat", 1.2f);
            }
        }
    }

    private void tickRunning() {
        elapsed++;
        for (Object o : plugin.config().list("events")) {
            if (o instanceof Map<?, ?> ev && ev.get("at") instanceof Number at && at.longValue() == elapsed) runEvent(ev);
        }
    }

    private void runEvent(Map<?, ?> ev) {
        String action = String.valueOf(ev.get("action")).toUpperCase(Locale.ROOT);
        switch (action) {
            case "GENERATOR_TIER" -> {
                try {
                    GeneratorType type = GeneratorType.valueOf(String.valueOf(ev.get("type")).toUpperCase(Locale.ROOT));
                    generatorTiers.put(type, ev.get("tier") instanceof Number n ? n.intValue() : 2);
                } catch (IllegalArgumentException ignored) {
                    // misconfigured event – skip silently
                }
            }
            case "BED_GONE", "SUDDEN_DEATH" -> {
                for (TeamState ts : teams.values()) {
                    if (ts.destroyBed()) {
                        TeamDefinition td = def.teamIfPresent(ts.color());
                        if (td != null && td.bed() != null && def.world() != null && Bukkit.getWorld(def.world()) != null) {
                            Location l = new Location(Bukkit.getWorld(def.world()), td.bed().x(), td.bed().y(), td.bed().z());
                            plugin.scheduler().region(l, () -> removeBedBlocks(l.getBlock()));
                        }
                    }
                }
            }
            case "END_GAME" -> end(null);
            default -> { }
        }
        if (ev.get("announce") != null) {
            Component c = plugin.messages().parse(String.valueOf(ev.get("announce")));
            forEachPlayer(p -> p.sendMessage(c));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Start
    // ═════════════════════════════════════════════════════════════════════════

    private void start() {
        if (!fsm.transition(ArenaState.STARTING, ArenaState.RUNNING)) return;
        startedAt = System.currentTimeMillis();
        elapsed = 0;
        generatorTiers.clear();
        assignTeams();
        cleanupEmptyTeamBeds();
        spawnNpcs();
        startGenerators();
        startBaseWatchers();
        broadcast("game-start");
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) spawnIntoGame(p, 0);
        }
    }

    private void cleanupEmptyTeamBeds() {
        for (TeamState ts : teams.values()) {
            if (ts.members().isEmpty()) {
                ts.destroyBed();
                TeamDefinition td = def.teamIfPresent(ts.color());
                if (td != null && td.bed() != null && def.world() != null && Bukkit.getWorld(def.world()) != null) {
                    Location l = new Location(Bukkit.getWorld(def.world()), td.bed().x(), td.bed().y(), td.bed().z());
                    plugin.scheduler().region(l, () -> removeBedBlocks(l.getBlock()));
                }
            }
        }
    }

    private void assignTeams() {
        teams.clear();
        assignment.clear();
        for (TeamColor c : def.activeColors()) {
            TeamDefinition td = def.teamIfPresent(c);
            if (td != null && td.isComplete()) teams.put(c, new TeamState(c));
        }
        List<UUID> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        for (UUID id : shuffled) {
            TeamState best = null;
            for (TeamState ts : teams.values()) {
                if (ts.members().size() >= def.teamSize()) continue;
                if (best == null || ts.members().size() < best.members().size()) best = ts;
            }
            if (best == null) continue;
            best.members().add(id);
            assignment.put(id, best.color());
        }
    }

    private void spawnIntoGame(Player p, int immuneSeconds) {
        UUID id = p.getUniqueId();
        TeamColor c = assignment.get(id);
        if (c == null) return;
        TeamState ts = teams.get(c);
        Location spawn = def.team(c).spawn().toLocation();
        if (immuneSeconds > 0) immuneUntil.put(id, System.currentTimeMillis() + immuneSeconds * 1000L);
        plugin.scheduler().entity(p, () -> {
            resetPlayer(p, GameMode.SURVIVAL);
            giveGear(p, c);
            applyTeamUpgrades(p, ts);
        });
        plugin.scheduler().teleport(p, spawn);
    }

    private void giveGear(Player p, TeamColor color) {
        PlayerInventory inv = p.getInventory();
        ItemStack sword = new ItemStack(Material.WOODEN_SWORD);
        sword.editMeta(m -> m.setUnbreakable(true));
        inv.addItem(sword);
        Material tier = armorTier.get(p.getUniqueId());
        inv.setHelmet(leather(Material.LEATHER_HELMET, color));
        inv.setChestplate(leather(Material.LEATHER_CHESTPLATE, color));
        if (tier != null) {
            Material legs = Material.matchMaterial(tier.name().replace("_BOOTS", "_LEGGINGS"));
            ItemStack boots = new ItemStack(tier);
            boots.editMeta(m -> m.setUnbreakable(true));
            inv.setBoots(boots);
            ItemStack leggings = new ItemStack(legs == null ? Material.LEATHER_LEGGINGS : legs);
            leggings.editMeta(m -> m.setUnbreakable(true));
            inv.setLeggings(leggings);
        } else {
            inv.setLeggings(leather(Material.LEATHER_LEGGINGS, color));
            inv.setBoots(leather(Material.LEATHER_BOOTS, color));
        }
    }

    private static ItemStack leather(Material type, TeamColor color) {
        ItemStack item = new ItemStack(type);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(color.armorColor());
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

    private void resetPlayer(Player p, GameMode mode) {
        p.getInventory().clear();
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setGameMode(mode);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setHealth(Compat.maxHealth(p));
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setLevel(0);
        p.setExp(0f);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Team upgrades
    // ═════════════════════════════════════════════════════════════════════════

    /** Re-applies enchants / haste to every online member of the team (entity-thread safe). */
    public void refreshUpgrades(TeamState ts) {
        for (UUID id : ts.members()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.scheduler().entity(p, () -> applyTeamUpgrades(p, ts));
        }
    }

    /** Must run on the player's entity thread. */
    public void applyTeamUpgrades(Player p, TeamState ts) {
        if (ts == null) return;
        Enchantment sharp = Compat.enchantment("sharpness");
        Enchantment prot = Compat.enchantment("protection");
        int sharpLevel = ts.level(TeamState.Upgrade.SHARPNESS);
        int protLevel = ts.level(TeamState.Upgrade.PROTECTION);
        PlayerInventory inv = p.getInventory();
        if (sharp != null && sharpLevel > 0) {
            for (ItemStack it : inv.getContents()) {
                if (it != null && it.getType().name().endsWith("_SWORD")) it.addUnsafeEnchantment(sharp, sharpLevel);
            }
        }
        if (prot != null && protLevel > 0) {
            ItemStack[] armor = inv.getArmorContents();
            for (ItemStack it : armor) if (it != null) it.addUnsafeEnchantment(prot, protLevel);
            inv.setArmorContents(armor);
        }
        int haste = ts.level(TeamState.Upgrade.HASTE);
        PotionEffectType type = Compat.effect("haste");
        if (type != null) {
            p.removePotionEffect(type);
            if (haste > 0) p.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, haste - 1, false, false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Generators  (one region-owned timer per generator → no cross-thread access)
    // ═════════════════════════════════════════════════════════════════════════

    private void startGenerators() {
        for (GeneratorDefinition g : def.generators()) {
            Location loc = g.location().toLocation();
            if (loc == null) continue;
            GenRuntime rt = new GenRuntime(g);
            tasks.add(plugin.scheduler().regionTimer(loc, t -> rt.tick(), 1, 5));
        }
    }

    private final class GenRuntime {
        private final GeneratorDefinition gen;
        private double acc;
        private double emeraldAcc;

        GenRuntime(GeneratorDefinition gen) { this.gen = gen; }

        void tick() {
            if (fsm.state() != ArenaState.RUNNING) return;
            Location loc = gen.location().toLocation();
            if (loc == null) return;
            TeamState owner = gen.owner() == null ? null : teams.get(gen.owner());
            if (gen.owner() != null && (owner == null || owner.eliminated())) return;

            GeneratorType.Tier tier = gen.type().tier(plugin.config(), generatorTier(gen.type()));
            double mult = gen.speedMultiplier();
            if (owner != null && gen.type().isTeamBase()) mult *= forgeMultiplier(owner.level(TeamState.Upgrade.FORGE));

            acc += 5 * mult;
            for (int guard = 0; acc >= tier.intervalTicks() && guard < 4; guard++) {
                acc -= tier.intervalTicks();
                drop(loc, gen.type().material(), tier, gen.splitDrop());
            }
            if (owner != null && gen.type() == GeneratorType.IRON
                    && owner.level(TeamState.Upgrade.FORGE) >= plugin.config().getInt("upgrades.forge.emerald-level", 3)) {
                emeraldAcc += 5;
                if (emeraldAcc >= plugin.config().getInt("upgrades.forge.emerald-interval", 1000)) {
                    emeraldAcc = 0;
                    drop(loc, Material.EMERALD, new GeneratorType.Tier(0, 1, 3), false);
                }
            }
        }
    }

    private double forgeMultiplier(int level) {
        List<?> list = plugin.config().list("upgrades.forge.multipliers");
        if (list.isEmpty()) return 1.0;
        Object v = list.get(Math.max(0, Math.min(list.size() - 1, level)));
        return v instanceof Number n ? n.doubleValue() : 1.0;
    }

    /** Runs on the generator's region thread. */
    private void drop(Location loc, Material mat, GeneratorType.Tier tier, boolean split) {
        World world = loc.getWorld();
        if (split) {
            double radius = plugin.config().getDouble("generators.radius-split", 3.0);
            for (Player p : loc.getNearbyPlayers(radius)) {
                if (!isPlaying(p.getUniqueId())) continue;
                plugin.scheduler().entity(p, () -> p.getInventory().addItem(new ItemStack(mat, tier.amount()))
                        .values().forEach(rest -> world.dropItem(p.getLocation(), rest)));
            }
            return;
        }
        int existing = 0;
        for (Item item : loc.getNearbyEntitiesByType(Item.class, 1.6)) {
            if (item.getItemStack().getType() == mat) existing += item.getItemStack().getAmount();
        }
        if (existing >= tier.max()) return;
        world.dropItem(loc.clone().add(0, 0.4, 0), new ItemStack(mat, tier.amount()), item -> item.setVelocity(new Vector(0, 0.05, 0)));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Traps (region timer on every team base)
    // ═════════════════════════════════════════════════════════════════════════

    private void startBaseWatchers() {
        for (TeamState ts : teams.values()) {
            Location base = def.team(ts.color()).spawn().toLocation();
            if (base == null) continue;
            tasks.add(plugin.scheduler().regionTimer(base, t -> watchBase(ts, base), 20, 10));
        }
    }

    private void watchBase(TeamState ts, Location base) {
        if (fsm.state() != ArenaState.RUNNING || ts.traps().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now < ts.trapCooldownUntil()) return;
        double radius = plugin.config().getDouble("upgrades.traps.trigger-radius", 6.0);
        for (Player intruder : base.getNearbyPlayers(radius)) {
            UUID id = intruder.getUniqueId();
            TeamColor pc = assignment.get(id);
            if (pc == null || pc == ts.color() || isSpectator(id) || !players.contains(id)) continue;
            TeamState.Trap trap = ts.traps().poll();
            if (trap == null) return;
            ts.trapCooldownUntil(now + plugin.config().getInt("upgrades.traps.cooldown-seconds", 30) * 1000L);
            fireTrap(trap, ts, intruder);
            return;
        }
    }

    private void fireTrap(TeamState.Trap trap, TeamState ts, Player intruder) {
        plugin.scheduler().entity(intruder, () -> {
            switch (trap) {
                case ITS_A_TRAP -> {
                    effect(intruder, "blindness", 160, 0);
                    effect(intruder, "slowness", 160, 1);
                }
                case MINER_FATIGUE -> effect(intruder, "mining_fatigue", 200, 0);
                case ALARM -> {
                    PotionEffectType inv = Compat.effect("invisibility");
                    if (inv != null) intruder.removePotionEffect(inv);
                }
                default -> { }
            }
        });
        for (UUID id : ts.members()) {
            Player member = Bukkit.getPlayer(id);
            if (member == null) continue;
            plugin.scheduler().entity(member, () -> {
                if (trap == TeamState.Trap.COUNTER_OFFENSIVE) {
                    effect(member, "speed", 200, 1);
                    effect(member, "jump_boost", 200, 1);
                }
                member.showTitle(Title.title(plugin.messages().get("trap-title"), Component.text(trap.display())));
                Msg.sound(member, "block.note_block.pling", 0.6f);
                member.sendMessage(plugin.messages().get("trap-triggered", Messages.text("trap", trap.display())));
            });
        }
    }

    private static void effect(Player p, String key, int ticks, int amplifier) {
        PotionEffectType type = Compat.effect(key);
        if (type != null) p.addPotionEffect(new PotionEffect(type, ticks, amplifier, false, true));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Beds, deaths, elimination, victory
    // ═════════════════════════════════════════════════════════════════════════

    /** Removes both halves of a bed and journals them for rollback. Region thread. */
    public void removeBedBlocks(Block clicked) {
        List<Block> parts = new ArrayList<>();
        parts.add(clicked);
        for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            Block n = clicked.getRelative(f);
            if (Tag.BEDS.isTagged(n.getType())) parts.add(n);
        }
        for (Block b : parts) {
            if (!Tag.BEDS.isTagged(b.getType())) continue;
            rollback.recordRemoved(b);
            b.setType(Material.AIR, false);
        }
    }

    /** @return the team whose bed block is at/adjacent to the given position, or null. */
    public TeamColor bedOwnerAt(Block block) {
        for (TeamState ts : teams.values()) {
            TeamDefinition td = def.teamIfPresent(ts.color());
            if (td == null || td.bed() == null || td.bed().y() != block.getY()) continue;
            if (Math.abs(td.bed().x() - block.getX()) + Math.abs(td.bed().z() - block.getZ()) <= 1) return ts.color();
        }
        return null;
    }

    public boolean bedBroken(TeamColor victimTeam, Player breaker) {
        TeamState ts = teams.get(victimTeam);
        if (ts == null || !ts.destroyBed()) return false;
        TeamColor bc = assignment.get(breaker.getUniqueId());
        stats(breaker.getUniqueId()).add(Stat.BEDS_BROKEN, 1);
        plugin.rewards().reward(breaker.getUniqueId(), "bed");
        broadcast("bed-destroyed", Messages.comp("team", teamName(victimTeam)),
                Messages.comp("player", bc == null ? Component.text(breaker.getName()) : coloredName(breaker, bc)));
        for (UUID id : ts.members()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            plugin.scheduler().entity(p, () -> {
                p.showTitle(Title.title(plugin.messages().get("bed-title"), plugin.messages().get("bed-subtitle")));
                Msg.sound(p, "entity.wither.death", 1.0f);
            });
        }
        return true;
    }

    /** Called (on the victim's thread) when damage would kill a participant. */
    public void handleLethal(Player victim, Player killer) {
        UUID vid = victim.getUniqueId();
        TeamColor vc = assignment.get(vid);
        if (vc == null || fsm.state() != ArenaState.RUNNING || isSpectator(vid)) return;
        TeamState vt = teams.get(vc);
        if (vt == null) return;
        boolean fin = !vt.bedAlive();
        stats(vid).add(fin ? Stat.FINAL_DEATHS : Stat.DEATHS, 1);

        boolean validKiller = killer != null && !killer.equals(victim) && isPlaying(killer.getUniqueId());
        Component vname = coloredName(victim, vc);
        if (validKiller) {
            TeamColor kc = assignment.get(killer.getUniqueId());
            stats(killer.getUniqueId()).add(fin ? Stat.FINAL_KILLS : Stat.KILLS, 1);
            plugin.rewards().reward(killer.getUniqueId(), fin ? "final-kill" : "kill");
            broadcast(fin ? "final-kill" : "kill", Messages.comp("victim", vname), Messages.comp("killer", coloredName(killer, kc)));
            transferLoot(victim, killer);
        } else {
            broadcast("death", Messages.comp("victim", vname));
        }
        playEffect(victim.getLocation(), plugin.config().getString(fin ? "effects.final-kill" : "effects.kill", "NONE"));

        victim.getInventory().clear();
        victim.setHealth(Compat.maxHealth(victim));
        victim.setFireTicks(0);
        victim.setFallDistance(0f);
        for (PotionEffect e : victim.getActivePotionEffects()) victim.removePotionEffect(e.getType());

        if (fin) eliminate(victim, vc);
        else startRespawn(victim);
    }

    private void transferLoot(Player victim, Player killer) {
        Map<Material, Integer> loot = new EnumMap<>(Material.class);
        for (ItemStack it : victim.getInventory().getContents()) {
            if (it != null && RESOURCES.contains(it.getType())) loot.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        if (loot.isEmpty()) return;
        plugin.scheduler().entity(killer, () -> loot.forEach((m, amount) ->
                killer.getInventory().addItem(new ItemStack(m, amount)).values()
                        .forEach(rest -> killer.getWorld().dropItem(killer.getLocation(), rest))));
    }

    private void startRespawn(Player victim) {
        UUID id = victim.getUniqueId();
        limbo.add(id);
        makeSpectator(victim);
        plugin.scheduler().teleport(victim, spectatorSpawn());
        AtomicInteger left = new AtomicInteger(plugin.config().getInt("game.respawn-delay", 5));
        plugin.scheduler().entityTimer(victim, task -> {
            if (fsm.state() != ArenaState.RUNNING || !players.contains(id) || spectators.contains(id)) {
                task.cancel();
                return;
            }
            int s = left.getAndDecrement();
            if (s <= 0) {
                task.cancel();
                limbo.remove(id);
                spawnIntoGame(victim, plugin.config().getInt("game.invulnerable-after-respawn", 3));
                return;
            }
            victim.showTitle(Title.title(plugin.messages().get("respawning", Messages.text("seconds", s)), Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)));
        }, 1, 20);
    }

    private void eliminate(Player p, TeamColor color) {
        UUID id = p.getUniqueId();
        spectators.add(id);
        TeamState ts = teams.get(color);
        if (ts != null) ts.members().remove(id);
        makeSpectator(p);
        plugin.scheduler().teleport(p, spectatorSpawn());
        if (ts != null && ts.eliminated()) broadcast("team-eliminated", Messages.comp("team", teamName(color)));
        checkWin();
    }

    /** Spectator flight system: invisible, flying, non-colliding, no game-mode noclip. */
    private void makeSpectator(Player p) {
        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setCollidable(false);
        PotionEffectType inv = Compat.effect("invisibility");
        if (inv != null) p.addPotionEffect(new PotionEffect(inv, PotionEffect.INFINITE_DURATION, 0, false, false));
    }

    private Location spectatorSpawn() {
        return def.spectatorSpawn() == null ? null : def.spectatorSpawn().toLocation();
    }

    private void checkWin() {
        if (fsm.state() != ArenaState.RUNNING) return;
        List<TeamState> alive = teams.values().stream().filter(t -> !t.eliminated()).toList();
        if (alive.size() <= 1) end(alive.isEmpty() ? null : alive.get(0).color());
    }

    private void end(TeamColor winner) {
        if (!fsm.transition(ArenaState.RUNNING, ArenaState.ENDING)) return;
        cancelGameTasks();
        countdown = plugin.config().getInt("game.ending-duration", 10);
        long endedAt = System.currentTimeMillis();
        int k = plugin.config().getInt("elo.k-factor", 24);

        Map<UUID, Long> eloBefore = new HashMap<>();
        for (UUID id : assignment.keySet()) eloBefore.put(id, stats(id).get(Stat.ELO));

        for (Map.Entry<UUID, TeamColor> e : assignment.entrySet()) {
            UUID id = e.getKey();
            if (!players.contains(id)) continue; // leavers already received their loss
            boolean won = e.getValue() == winner;
            double opponents = assignment.entrySet().stream()
                    .filter(o -> o.getValue() != e.getValue() && players.contains(o.getKey()))
                    .mapToLong(o -> eloBefore.getOrDefault(o.getKey(), 1000L)).average().orElse(eloBefore.get(id));
            PlayerProfile profile = stats(id);
            profile.add(Stat.ELO, EloCalculator.delta(eloBefore.get(id), opponents, won, k));
            profile.recordResult(won);
            if (won) plugin.rewards().reward(id, "win");
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.showTitle(Title.title(
                        Component.text(won ? "VICTORY!" : "GAME OVER", won ? net.kyori.adventure.text.format.NamedTextColor.GOLD
                                : net.kyori.adventure.text.format.NamedTextColor.RED),
                        Component.text(winner == null ? "Draw" : winner.displayName() + " team won", net.kyori.adventure.text.format.NamedTextColor.GRAY)));
                Msg.sound(p, won ? "ui.toast.challenge_complete" : "entity.wither.spawn", 1.0f);
            }
        }
        if (winner != null) broadcast("victory", Messages.comp("team", teamName(winner)));
        else broadcast("draw");

        plugin.database().recordMatch(new MatchRecord(plugin.config().getString("server-id", "bw-1"), def.id(), def.modeName(),
                winner == null ? null : winner.name(), (endedAt - startedAt) / 1000, startedAt, endedAt));
        plugin.scheduler().async(plugin.stats()::flushAll);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Reset / shutdown
    // ═════════════════════════════════════════════════════════════════════════

    private void reset() {
        if (!fsm.transition(ArenaState.ENDING, ArenaState.RESETTING)) return;
        cancelGameTasks();
        despawnNpcs();
        for (UUID id : new ArrayList<>(players)) {
            players.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) restore(p);
        }
        spectators.clear();
        limbo.clear();
        assignment.clear();
        teams.clear();
        lastDamage.clear();
        armorTier.clear();
        immuneUntil.clear();
        rollback.restore().whenComplete((v, ex) -> {
            if (ex != null) plugin.getLogger().warning("Rollback of '" + def.id() + "' failed: " + ex.getMessage());
            fsm.transition(ArenaState.RESETTING, ArenaState.WAITING);
        });
    }

    /** Immediate stop (arena disabled / plugin shutting down). Best-effort rollback. */
    public void shutdown() {
        loop.cancel();
        cancelGameTasks();
        despawnNpcs();
        fsm.transition(ArenaState.DISABLED);
        for (UUID id : new ArrayList<>(players)) {
            players.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) restore(p);
        }
        // NOTE: during onDisable the schedulers are already shutting down; on a live disable this completes
        // normally. Persisting the journal across crashes is on the roadmap (see docs).
        rollback.restore();
    }

    private void cancelGameTasks() {
        for (ScheduledTask t : tasks) t.cancel();
        tasks.clear();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NPCs
    // ═════════════════════════════════════════════════════════════════════════

    private void spawnNpcs() {
        for (TeamColor c : teams.keySet()) {
            TeamDefinition td = def.team(c);
            spawnNpc(td.shopNpc() == null ? null : td.shopNpc().toLocation(), "shop");
            spawnNpc(td.upgradeNpc() == null ? null : td.upgradeNpc().toLocation(), "upgrade");
        }
    }

    private void spawnNpc(Location loc, String kind) {
        if (loc == null) return;
        npcLocations.add(loc);
        plugin.scheduler().region(loc, () -> {
            Villager v = loc.getWorld().spawn(loc, Villager.class, e -> {
                e.customName(Msg.mm(kind.equals("shop") ? "<yellow><bold>ITEM SHOP" : "<aqua><bold>TEAM UPGRADES"));
                e.setCustomNameVisible(true);
                e.setAI(false);
                e.setInvulnerable(true);
                e.setSilent(true);
                e.setPersistent(false);
                e.setCollidable(false);
                e.getPersistentDataContainer().set(plugin.npcKey(), PersistentDataType.STRING, kind + ":" + def.id());
            });
            npcs.add(v.getUniqueId());
        });
    }

    private void despawnNpcs() {
        for (Location loc : npcLocations) {
            if (loc == null || loc.getWorld() == null) continue;
            plugin.scheduler().region(loc, () -> {
                for (Entity e : loc.getWorld().getNearbyEntities(loc, 2.0, 2.0, 2.0)) {
                    if (e instanceof Villager) {
                        String tag = e.getPersistentDataContainer().get(plugin.npcKey(), PersistentDataType.STRING);
                        if (tag != null && tag.endsWith(":" + def.id())) {
                            e.remove();
                        }
                    }
                }
            });
        }
        npcLocations.clear();
        npcs.clear();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private PlayerProfile stats(UUID id) { return plugin.stats().profile(id); }

    private Component coloredName(Player p, TeamColor c) { return Component.text(p.getName(), c.text()); }

    private Component teamName(TeamColor c) { return Component.text(c.displayName(), c.text()); }

    private void forEachPlayer(java.util.function.Consumer<Player> action) {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) action.accept(p);
        }
    }

    public void broadcast(String key, TagResolver... resolvers) {
        Component c = plugin.messages().get(key, resolvers);
        forEachPlayer(p -> p.sendMessage(c));
    }

    private void playEffect(Location loc, String type) {
        String t = type.toUpperCase(Locale.ROOT);
        if (t.equals("NONE")) return;
        plugin.scheduler().region(loc, () -> {
            World w = loc.getWorld();
            Location at = loc.clone().add(0, 1, 0);
            switch (t) {
                case "LIGHTNING" -> w.strikeLightningEffect(loc);
                case "FIREWORK" -> {
                    Particle part = Compat.particle("firework");
                    if (part != null) w.spawnParticle(part, at, 40, 0.3, 0.5, 0.3, 0.1);
                }
                case "SOUL" -> {
                    Particle part = Compat.particle("soul");
                    if (part != null) w.spawnParticle(part, at, 30, 0.3, 0.5, 0.3, 0.05);
                }
                case "BLOOD" -> {
                    Particle part = Compat.particle("block");
                    if (part != null) w.spawnParticle(part, at, 30, 0.3, 0.5, 0.3, Material.REDSTONE_BLOCK.createBlockData());
                }
                default -> { }
            }
        });
    }
}
