package dev.nexus.bedwars.gui;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Small MiniMessage / Adventure helpers shared by all GUIs. */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msg() {}

    /** Parses MiniMessage and removes the default italic (for item names / lore). */
    public static Component mm(String input, TagResolver... resolvers) {
        return MM.deserialize(input, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static String escape(String input) {
        return MM.escapeTags(input);
    }

    public static ItemStack filler() {
        return ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name("<dark_gray> ").build();
    }

    /** Version-proof sound playback by resource key (no Sound enum access). */
    public static void sound(Audience audience, String key, float pitch) {
        audience.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, 0.8f, pitch));
    }

    public static void click(Audience audience) {
        sound(audience, "ui.button.click", 1.0f);
    }

    public static void success(Audience audience) {
        sound(audience, "entity.experience_orb.pickup", 1.4f);
    }

    public static void error(Audience audience) {
        sound(audience, "entity.villager.no", 1.0f);
    }
}
