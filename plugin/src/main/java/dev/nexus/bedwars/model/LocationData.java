package dev.nexus.bedwars.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable, serialisable location – safe to share between threads (unlike {@link Location}). */
public record LocationData(String world, double x, double y, double z, float yaw, float pitch) {

    public static LocationData of(Location l) {
        return new LocationData(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    /** Snaps to the middle of the block (used for NPCs and generators). */
    public static LocationData centered(Location l) {
        return new LocationData(l.getWorld().getName(), l.getBlockX() + 0.5, l.getBlockY(), l.getBlockZ() + 0.5, l.getYaw(), 0f);
    }

    /** Returns null if the world is not loaded. */
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public String pretty() {
        return String.format("%s %.1f, %.1f, %.1f", world, x, y, z);
    }

    public double distanceSquared(double ox, double oy, double oz) {
        double dx = x - ox, dy = y - oy, dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    public void write(ConfigurationSection parent, String key) {
        ConfigurationSection s = parent.createSection(key);
        s.set("world", world);
        s.set("x", round(x));
        s.set("y", round(y));
        s.set("z", round(z));
        s.set("yaw", round(yaw));
        s.set("pitch", round(pitch));
    }

    public static LocationData read(ConfigurationSection parent, String key) {
        ConfigurationSection s = parent.getConfigurationSection(key);
        if (s == null || s.getString("world") == null) return null;
        return new LocationData(s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
