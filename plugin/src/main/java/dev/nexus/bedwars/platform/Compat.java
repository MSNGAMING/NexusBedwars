package dev.nexus.bedwars.platform;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Version-abstraction layer.
 *
 * <p>The API breaks that matter between 1.21 and 26.x are all of the same kind: Bukkit
 * enum constants becoming registry-backed objects and being renamed along the way
 * (e.g. {@code GENERIC_MAX_HEALTH → MAX_HEALTH}, {@code PROTECTION_ENVIRONMENTAL → PROTECTION},
 * {@code FIREWORKS_SPARK → FIREWORK}). Looking everything up by its stable Minecraft
 * resource key through {@link Registry} is immune to those renames, so no NMS or
 * per-version module is needed.
 *
 * <p>If a future version ever needs real NMS, add an implementation of a small interface
 * here and select it by {@code Bukkit.getMinecraftVersion()} – callers stay untouched.
 */
public final class Compat {

    private Compat() {}

    public static Enchantment enchantment(String key) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    public static PotionEffectType effect(String key) {
        return Registry.EFFECT.get(NamespacedKey.minecraft(key));
    }

    public static Attribute attribute(String key) {
        Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
        if (attr == null && !key.startsWith("generic.")) {
            attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic." + key));
        }
        return attr;
    }

    public static Particle particle(String key) {
        return Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(key));
    }

    public static double maxHealth(Player player) {
        Attribute attribute = attribute("max_health");
        if (attribute == null) {
            return 20.0;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? 20.0 : instance.getValue();
    }
}
