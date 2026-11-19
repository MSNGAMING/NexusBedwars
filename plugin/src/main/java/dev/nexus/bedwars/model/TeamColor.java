package dev.nexus.bedwars.model;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Locale;

/** The eight team colours (enough for Solo / Doubles; 3v3v3v3 and 4v4v4v4 use the first four). */
public enum TeamColor {
    RED(NamedTextColor.RED, "RED", Color.RED),
    BLUE(NamedTextColor.BLUE, "BLUE", Color.BLUE),
    GREEN(NamedTextColor.GREEN, "LIME", Color.LIME),
    YELLOW(NamedTextColor.YELLOW, "YELLOW", Color.YELLOW),
    AQUA(NamedTextColor.AQUA, "CYAN", Color.AQUA),
    WHITE(NamedTextColor.WHITE, "WHITE", Color.WHITE),
    PINK(NamedTextColor.LIGHT_PURPLE, "PINK", Color.FUCHSIA),
    GRAY(NamedTextColor.DARK_GRAY, "GRAY", Color.GRAY);

    private final NamedTextColor text;
    private final String materialPrefix;
    private final Color armorColor;

    TeamColor(NamedTextColor text, String materialPrefix, Color armorColor) {
        this.text = text;
        this.materialPrefix = materialPrefix;
        this.armorColor = armorColor;
    }

    public NamedTextColor text() { return text; }

    public Color armorColor() { return armorColor; }

    public String displayName() {
        String n = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** e.g. RED + "WOOL" → RED_WOOL, GREEN + "WOOL" → LIME_WOOL. */
    public Material material(String suffix) {
        Material m = Material.matchMaterial(materialPrefix + "_" + suffix);
        return m == null ? Material.WHITE_WOOL : m;
    }

    public Material wool() { return material("WOOL"); }

    public static TeamColor parse(String s) {
        try {
            return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
