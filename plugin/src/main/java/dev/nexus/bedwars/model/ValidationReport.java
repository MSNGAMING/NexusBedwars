package dev.nexus.bedwars.model;

import dev.nexus.bedwars.model.ArenaDefinition.GeneratorDefinition;
import dev.nexus.bedwars.model.ArenaDefinition.TeamDefinition;
import dev.nexus.bedwars.setup.SetupStep;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;

/**
 * The interactive validation checklist. An arena can only be enabled when
 * {@link #valid()} is true – enforced both in the GUI and in {@code ArenaManager#enable}.
 */
public record ValidationReport(List<Check> checks) {

    public record Check(String name, boolean passed, String detail, SetupStep step) {}

    public boolean valid() {
        return checks.stream().allMatch(Check::passed);
    }

    public long passedCount() {
        return checks.stream().filter(Check::passed).count();
    }

    public List<Check> failing() {
        return checks.stream().filter(c -> !c.passed()).toList();
    }

    public boolean stepComplete(SetupStep step) {
        return checks.stream().filter(c -> c.step() == step).allMatch(Check::passed);
    }

    public static ValidationReport of(ArenaDefinition a) {
        List<Check> c = new ArrayList<>();

        // ── Region ───────────────────────────────────────────────────────────
        c.add(new Check("Region selected (pos1 + pos2)", a.hasRegion(),
                a.hasRegion() ? "World " + a.world() : "Set both corners", SetupStep.REGION));
        boolean worldLoaded = a.world() != null && Bukkit.getWorld(a.world()) != null;
        c.add(new Check("Arena world is loaded", worldLoaded,
                worldLoaded ? a.world() : "World '" + a.world() + "' is not loaded", SetupStep.REGION));

        // ── Lobby & spectator ────────────────────────────────────────────────
        c.add(new Check("Waiting lobby spawn", a.waitingLobby() != null,
                a.waitingLobby() != null ? a.waitingLobby().pretty() : "Not set", SetupStep.LOBBY));
        c.add(new Check("Spectator spawn", a.spectatorSpawn() != null,
                a.spectatorSpawn() != null ? a.spectatorSpawn().pretty() : "Not set", SetupStep.SPECTATOR));

        // ── Teams ────────────────────────────────────────────────────────────
        for (TeamColor color : a.activeColors()) {
            TeamDefinition t = a.team(color);
            List<String> missing = new ArrayList<>();
            if (t.spawn() == null) missing.add("spawn");
            if (t.bed() == null) missing.add("bed");
            if (t.shopNpc() == null) missing.add("shop NPC");
            if (t.upgradeNpc() == null) missing.add("upgrade NPC");
            c.add(new Check("Team " + color.displayName() + " configured", missing.isEmpty(),
                    missing.isEmpty() ? "All 4 points set" : "Missing: " + String.join(", ", missing), SetupStep.TEAMS));
        }
        boolean spawnsInside = a.hasRegion() && a.activeColors().stream()
                .map(a::team).filter(t -> t.spawn() != null)
                .allMatch(t -> a.contains(t.spawn().world(), t.spawn().x(), t.spawn().y(), t.spawn().z()));
        c.add(new Check("Team spawns inside region", spawnsInside,
                spawnsInside ? "OK" : "At least one spawn is outside the selected region", SetupStep.TEAMS));

        // ── Generators ───────────────────────────────────────────────────────
        for (TeamColor color : a.activeColors()) {
            boolean iron = hasGen(a, GeneratorType.IRON, color), gold = hasGen(a, GeneratorType.GOLD, color);
            c.add(new Check("Team " + color.displayName() + " base generators", iron && gold,
                    (iron ? "iron ✔ " : "iron ✘ ") + (gold ? "gold ✔" : "gold ✘"), SetupStep.GENERATORS));
        }
        long diamonds = a.generators().stream().filter(g -> g.type() == GeneratorType.DIAMOND).count();
        long emeralds = a.generators().stream().filter(g -> g.type() == GeneratorType.EMERALD).count();
        c.add(new Check("At least one diamond generator", diamonds > 0, diamonds + " placed", SetupStep.GENERATORS));
        c.add(new Check("At least one emerald generator", emeralds > 0, emeralds + " placed", SetupStep.GENERATORS));

        // ── Settings ─────────────────────────────────────────────────────────
        boolean players = a.minPlayers() >= 2 && a.minPlayers() <= a.maxPlayers();
        c.add(new Check("Player limits valid", players,
                "min " + a.minPlayers() + " / max " + a.maxPlayers() + " (" + a.modeName() + ")", SetupStep.SETTINGS));
        return new ValidationReport(List.copyOf(c));
    }

    private static boolean hasGen(ArenaDefinition a, GeneratorType type, TeamColor owner) {
        for (GeneratorDefinition g : a.generators()) {
            if (g.type() == type && g.owner() == owner) return true;
        }
        return false;
    }
}
