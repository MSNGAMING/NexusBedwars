package dev.nexus.bedwars.model;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

/** World-agnostic, immutable block coordinate. */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(Block block) {
        return new BlockPos(block.getX(), block.getY(), block.getZ());
    }

    public static BlockPos of(Location loc) {
        return new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public int chunkX() { return x >> 4; }

    public int chunkZ() { return z >> 4; }

    public double distanceSquared(double ox, double oy, double oz) {
        double dx = x + 0.5 - ox, dy = y + 0.5 - oy, dz = z + 0.5 - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    public String pretty() {
        return x + ", " + y + ", " + z;
    }

    public void write(ConfigurationSection parent, String key) {
        ConfigurationSection s = parent.createSection(key);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
    }

    public static BlockPos read(ConfigurationSection parent, String key) {
        ConfigurationSection s = parent.getConfigurationSection(key);
        if (s == null) return null;
        return new BlockPos(s.getInt("x"), s.getInt("y"), s.getInt("z"));
    }
}
