package dev.nexus.bedwars.arena;

import dev.nexus.bedwars.model.TeamColor;
import org.bukkit.Material;

import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;

/** Mutable runtime state of one team during a round. Everything here is thread-safe. */
public final class TeamState {

    /** Purchasable team upgrades (costs/levels come from config.yml → upgrades.&lt;key&gt;). */
    public enum Upgrade {
        SHARPNESS("sharpness"), PROTECTION("protection"), HASTE("haste"), FORGE("forge");

        private final String key;

        Upgrade(String key) { this.key = key; }

        public String key() { return key; }
    }

    /** Queueable base traps. */
    public enum Trap {
        ITS_A_TRAP("It's a Trap!", Material.TRIPWIRE_HOOK, "Blindness & slowness for the intruder"),
        COUNTER_OFFENSIVE("Counter-Offensive", Material.FEATHER, "Speed & jump boost for your team"),
        ALARM("Alarm Trap", Material.REDSTONE_TORCH, "Reveals invisible intruders"),
        MINER_FATIGUE("Miner Fatigue", Material.IRON_PICKAXE, "Mining fatigue for the intruder");

        private final String display;
        private final Material icon;
        private final String description;

        Trap(String display, Material icon, String description) {
            this.display = display;
            this.icon = icon;
            this.description = description;
        }

        public String display() { return display; }
        public Material icon() { return icon; }
        public String description() { return description; }
    }

    private final TeamColor color;
    private final AtomicBoolean bedAlive = new AtomicBoolean(true);
    private final Set<UUID> members = ConcurrentHashMap.newKeySet();   // alive (not eliminated) members
    private final AtomicIntegerArray levels = new AtomicIntegerArray(Upgrade.values().length);
    private final Deque<Trap> traps = new ConcurrentLinkedDeque<>();
    private volatile long trapCooldownUntil;

    public TeamState(TeamColor color) {
        this.color = color;
    }

    public TeamColor color() { return color; }

    public boolean bedAlive() { return bedAlive.get(); }

    /** @return true if this call destroyed the bed. */
    public boolean destroyBed() { return bedAlive.compareAndSet(true, false); }

    public Set<UUID> members() { return members; }

    public boolean eliminated() { return members.isEmpty(); }

    public int level(Upgrade u) { return levels.get(u.ordinal()); }

    public void level(Upgrade u, int level) { levels.set(u.ordinal(), level); }

    public Deque<Trap> traps() { return traps; }

    public long trapCooldownUntil() { return trapCooldownUntil; }

    public void trapCooldownUntil(long v) { this.trapCooldownUntil = v; }

    public Map<Upgrade, Integer> upgradeSnapshot() {
        Map<Upgrade, Integer> map = new EnumMap<>(Upgrade.class);
        for (Upgrade u : Upgrade.values()) map.put(u, level(u));
        return map;
    }
}
