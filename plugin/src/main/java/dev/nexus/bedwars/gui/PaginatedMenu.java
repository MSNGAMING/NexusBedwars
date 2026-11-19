package dev.nexus.bedwars.gui;

import dev.nexus.bedwars.BedwarsPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Generic 5-row paginated list (21 entries per page). Subclasses provide the entries, their
 * icon and the click behaviour; navigation (prev / next / back / page indicator) is built in.
 */
public abstract class PaginatedMenu<T> extends Menu {

    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    private int page;

    protected PaginatedMenu(BedwarsPlugin plugin, Component title) {
        super(plugin, title, 5);
    }

    protected abstract List<T> entries();

    protected abstract ItemStack icon(T entry);

    protected abstract void select(T entry, MenuClick click);

    protected abstract void back(Player player);

    /** Optional extra buttons in the top row / nav row (slots 0–8 and 37–43). */
    protected void renderHeader() {}

    @Override
    protected final void render() {
        List<T> all = entries();
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) SLOTS.length));
        page = Math.max(0, Math.min(page, pages - 1));

        int from = page * SLOTS.length;
        for (int i = 0; i < SLOTS.length && from + i < all.size(); i++) {
            T entry = all.get(from + i);
            set(SLOTS[i], icon(entry), click -> select(entry, click));
        }

        renderHeader();

        set(36, ItemBuilder.of(Material.ARROW).name("<yellow>« Back").build(), c -> back(c.player()));
        if (page > 0) {
            set(39, ItemBuilder.of(Material.SPECTRAL_ARROW).name("<yellow>« Previous page").build(), c -> {
                page--;
                rebuild();
            });
        }
        set(40, ItemBuilder.of(Material.PAPER).name("<gray>Page <white>" + (page + 1) + "<gray>/<white>" + pages)
                .lore("<dark_gray>" + all.size() + " entries").build());
        if (page < pages - 1) {
            set(41, ItemBuilder.of(Material.SPECTRAL_ARROW).name("<yellow>Next page »").build(), c -> {
                page++;
                rebuild();
            });
        }
        fillEmpty(Msg.filler());
    }
}
