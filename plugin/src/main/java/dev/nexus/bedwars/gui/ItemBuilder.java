package dev.nexus.bedwars.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** Fluent MiniMessage item builder – every GUI icon in the plugin is created through this. */
public final class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;
    private final List<Component> lore = new ArrayList<>();

    private ItemBuilder(Material material) {
        this.item = new ItemStack(material);
        this.meta = item.getItemMeta();
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material);
    }

    public ItemBuilder name(String miniMessage, TagResolver... resolvers) {
        meta.displayName(Msg.mm(miniMessage, resolvers));
        return this;
    }

    public ItemBuilder name(Component component) {
        meta.displayName(component);
        return this;
    }

    public ItemBuilder lore(String... lines) {
        for (String l : lines) lore.add(Msg.mm(l));
        return this;
    }

    public ItemBuilder lore(List<String> lines) {
        for (String l : lines) lore.add(Msg.mm(l));
        return this;
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(Math.max(1, Math.min(64, amount)));
        return this;
    }

    public ItemBuilder glow(boolean glow) {
        if (glow) meta.setEnchantmentGlintOverride(true);
        return this;
    }

    public ItemBuilder tag(NamespacedKey key, String value) {
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        return this;
    }

    public ItemStack build() {
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
