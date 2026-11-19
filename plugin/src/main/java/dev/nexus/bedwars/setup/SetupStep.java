package dev.nexus.bedwars.setup;

import org.bukkit.Material;

/** Ordered steps of the setup wizard. */
public enum SetupStep {
    REGION("Arena Region", Material.GOLDEN_AXE, "Select the two corners of the arena."),
    LOBBY("Waiting Lobby", Material.OAK_DOOR, "Where players wait before the game."),
    SPECTATOR("Spectator Spawn", Material.ENDER_EYE, "Where dead / eliminated players fly."),
    TEAMS("Teams", Material.RED_BED, "Spawn, bed, shop & upgrade NPC for every team."),
    GENERATORS("Generators", Material.DIAMOND, "Iron, gold, diamond and emerald generators."),
    SETTINGS("Settings", Material.COMPARATOR, "Mode, team size, player limits and name."),
    REVIEW("Review & Enable", Material.EMERALD_BLOCK, "Validation checklist – enable the arena.");

    private final String title;
    private final Material icon;
    private final String description;

    SetupStep(String title, Material icon, String description) {
        this.title = title;
        this.icon = icon;
        this.description = description;
    }

    public String title() { return title; }
    public Material icon() { return icon; }
    public String description() { return description; }

    public SetupStep next() {
        SetupStep[] all = values();
        return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
    }
}
