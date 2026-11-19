package dev.nexus.bedwars.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mutable, thread-safe description of an arena (what gets persisted to arenas.yml).
 * Fields are volatile and collections concurrent: the setup GUI (entity threads), the command
 * layer, the hot-reloader (async) and running games all read/write it without extra locking.
 */
public final class ArenaDefinition {

    // ── nested: team & generator definitions ─────────────────────────────────

    public static final class TeamDefinition {
        private final TeamColor color;
        private volatile LocationData spawn;
        private volatile BlockPos bed;
        private volatile LocationData shopNpc;
        private volatile LocationData upgradeNpc;

        public TeamDefinition(TeamColor color) { this.color = color; }

        public TeamColor color() { return color; }
        public LocationData spawn() { return spawn; }
        public BlockPos bed() { return bed; }
        public LocationData shopNpc() { return shopNpc; }
        public LocationData upgradeNpc() { return upgradeNpc; }
        public void spawn(LocationData v) { this.spawn = v; }
        public void bed(BlockPos v) { this.bed = v; }
        public void shopNpc(LocationData v) { this.shopNpc = v; }
        public void upgradeNpc(LocationData v) { this.upgradeNpc = v; }

        public boolean isComplete() {
            return spawn != null && bed != null && shopNpc != null && upgradeNpc != null;
        }
    }

    public static final class GeneratorDefinition {
        private final String id;
        private final GeneratorType type;
        private final LocationData location;
        private volatile TeamColor owner;          // null → public generator (diamond / emerald / middle gold)
        private volatile boolean splitDrop;        // split-generation: drop goes straight to nearby players
        private volatile double speedMultiplier = 1.0;

        public GeneratorDefinition(String id, GeneratorType type, LocationData location, TeamColor owner) {
            this.id = id;
            this.type = type;
            this.location = location;
            this.owner = owner;
        }

        public String id() { return id; }
        public GeneratorType type() { return type; }
        public LocationData location() { return location; }
        public TeamColor owner() { return owner; }
        public void owner(TeamColor c) { this.owner = c; }
        public boolean splitDrop() { return splitDrop; }
        public void splitDrop(boolean v) { this.splitDrop = v; }
        public double speedMultiplier() { return speedMultiplier; }
        public void speedMultiplier(double v) { this.speedMultiplier = v; }
    }

    // ── fields ───────────────────────────────────────────────────────────────

    private final String id;
    private volatile String displayName;
    private volatile String world;
    private volatile BlockPos pos1;
    private volatile BlockPos pos2;
    private volatile LocationData waitingLobby;
    private volatile LocationData spectatorSpawn;
    private volatile int teamCount = 4;
    private volatile int teamSize = 2;
    private volatile int minPlayers = 2;
    private volatile boolean enabled;

    private final Map<TeamColor, TeamDefinition> teams = new ConcurrentHashMap<>();
    private final List<GeneratorDefinition> generators = new CopyOnWriteArrayList<>();

    public ArenaDefinition(String id) {
        this.id = id;
        this.displayName = id;
    }

    // ── simple accessors ─────────────────────────────────────────────────────

    public String id() { return id; }
    public String displayName() { return displayName; }
    public void displayName(String v) { this.displayName = v; }
    public String world() { return world; }
    public void world(String v) { this.world = v; }
    public BlockPos pos1() { return pos1; }
    public BlockPos pos2() { return pos2; }
    public void pos1(BlockPos p) { this.pos1 = p; }
    public void pos2(BlockPos p) { this.pos2 = p; }
    public LocationData waitingLobby() { return waitingLobby; }
    public void waitingLobby(LocationData l) { this.waitingLobby = l; }
    public LocationData spectatorSpawn() { return spectatorSpawn; }
    public void spectatorSpawn(LocationData l) { this.spectatorSpawn = l; }
    public int teamCount() { return teamCount; }
    public void teamCount(int v) { this.teamCount = Math.max(2, Math.min(TeamColor.values().length, v)); }
    public int teamSize() { return teamSize; }
    public void teamSize(int v) { this.teamSize = Math.max(1, Math.min(16, v)); }
    public int minPlayers() { return minPlayers; }
    public void minPlayers(int v) { this.minPlayers = Math.max(2, Math.min(Math.max(2, maxPlayers()), v)); }
    public boolean enabled() { return enabled; }
    public void enabled(boolean v) { this.enabled = v; }

    // ── derived ──────────────────────────────────────────────────────────────

    public int maxPlayers() { return teamCount * teamSize; }

    /** Human-readable mode label: Solo, Doubles, 3v3v3v3, 4v4v4v4 or a custom NxM string. */
    public String modeName() {
        if (teamCount == 8 && teamSize == 1) return "Solo";
        if (teamCount == 8 && teamSize == 2) return "Doubles";
        if (teamCount == 4 && teamSize == 3) return "3v3v3v3";
        if (teamCount == 4 && teamSize == 4) return "4v4v4v4";
        return "Custom (" + teamCount + "×" + teamSize + ")";
    }

    public List<TeamColor> activeColors() {
        return new ArrayList<>(Arrays.asList(TeamColor.values()).subList(0, teamCount));
    }

    public boolean hasRegion() { return pos1 != null && pos2 != null && world != null; }

    public BlockPos min() {
        return new BlockPos(Math.min(pos1.x(), pos2.x()), Math.min(pos1.y(), pos2.y()), Math.min(pos1.z(), pos2.z()));
    }

    public BlockPos max() {
        return new BlockPos(Math.max(pos1.x(), pos2.x()), Math.max(pos1.y(), pos2.y()), Math.max(pos1.z(), pos2.z()));
    }

    public boolean contains(String worldName, double x, double y, double z) {
        if (!hasRegion() || !world.equals(worldName)) return false;
        BlockPos lo = min(), hi = max();
        return x >= lo.x() && x < hi.x() + 1 && y >= lo.y() && y < hi.y() + 1 && z >= lo.z() && z < hi.z() + 1;
    }

    // ── teams & generators ───────────────────────────────────────────────────

    /** Gets (or lazily creates) the definition of a team. */
    public TeamDefinition team(TeamColor color) {
        return teams.computeIfAbsent(color, TeamDefinition::new);
    }

    public TeamDefinition teamIfPresent(TeamColor color) { return teams.get(color); }

    public Map<TeamColor, TeamDefinition> teams() { return teams; }

    public List<GeneratorDefinition> generators() { return generators; }

    public GeneratorDefinition addGenerator(GeneratorType type, LocationData loc, TeamColor owner) {
        GeneratorDefinition g = new GeneratorDefinition(UUID.randomUUID().toString().substring(0, 6), type, loc, owner);
        generators.add(g);
        generators.sort(Comparator.comparing(GeneratorDefinition::type).thenComparing(GeneratorDefinition::id));
        return g;
    }

    public boolean removeGenerator(GeneratorDefinition g) { return generators.remove(g); }
}
