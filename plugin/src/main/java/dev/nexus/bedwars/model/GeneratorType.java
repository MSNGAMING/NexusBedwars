package dev.nexus.bedwars.model;

import dev.nexus.bedwars.config.ConfigService;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/** Resource generator kinds. Drop tiers/speeds live in config.yml (generators.&lt;type&gt;.tiers). */
public enum GeneratorType {
    IRON(Material.IRON_INGOT, NamedTextColor.WHITE, 20, 48),
    GOLD(Material.GOLD_INGOT, NamedTextColor.GOLD, 160, 12),
    DIAMOND(Material.DIAMOND, NamedTextColor.AQUA, 600, 4),
    EMERALD(Material.EMERALD, NamedTextColor.GREEN, 1200, 3);

    /** One drop-tier of a generator. */
    public record Tier(int intervalTicks, int amount, int max) {}

    private final Material material;
    private final NamedTextColor color;
    private final int defaultInterval;
    private final int defaultMax;

    GeneratorType(Material material, NamedTextColor color, int defaultInterval, int defaultMax) {
        this.material = material;
        this.color = color;
        this.defaultInterval = defaultInterval;
        this.defaultMax = defaultMax;
    }

    public Material material() { return material; }

    public NamedTextColor color() { return color; }

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public boolean isTeamBase() { return this == IRON || this == GOLD; }

    /** Resolves tier {@code tier} (1-based, clamped) from the live configuration. */
    public Tier tier(ConfigService cfg, int tier) {
        List<?> tiers = cfg.list("generators." + key() + ".tiers");
        if (tiers.isEmpty()) return new Tier(defaultInterval, 1, defaultMax);
        Object entry = tiers.get(Math.max(0, Math.min(tiers.size() - 1, tier - 1)));
        if (entry instanceof java.util.Map<?, ?> m) {
            return new Tier(num(m.get("interval"), defaultInterval), num(m.get("amount"), 1), num(m.get("max"), defaultMax));
        }
        return new Tier(defaultInterval, 1, defaultMax);
    }

    public int maxTier(ConfigService cfg) {
        return Math.max(1, cfg.list("generators." + key() + ".tiers").size());
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }
}
