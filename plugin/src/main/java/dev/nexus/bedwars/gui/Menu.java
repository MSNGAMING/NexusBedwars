package dev.nexus.bedwars.gui;

import dev.nexus.bedwars.BedwarsPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Base class of every GUI. A menu is its own {@link InventoryHolder}, so clicks can be routed
 * without any global registry. Opening goes through the player's entity scheduler (Folia-safe);
 * click callbacks are invoked by Bukkit on that same thread, so they may freely touch the player.
 *
 * <p>"Dynamic visual feedback": call {@link #rebuild()} inside a click action and the icons are
 * redrawn instantly from the live model.
 */
public abstract class Menu implements InventoryHolder {

    protected final BedwarsPlugin plugin;
    private final Inventory inventory;
    private final int size;
    private final Map<Integer, Consumer<MenuClick>> actions = new HashMap<>();

    protected Menu(BedwarsPlugin plugin, Component title, int rows) {
        this.plugin = plugin;
        this.size = rows * 9;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    /** Draw all icons with {@link #set}. Called on every (re)build. */
    protected abstract void render();

    public final void open(Player player) {
        plugin.scheduler().entity(player, () -> {
            rebuild();
            player.openInventory(inventory);
        });
    }

    public final void rebuild() {
        actions.clear();
        inventory.clear();
        render();
    }

    protected final void set(int slot, ItemStack item, Consumer<MenuClick> action) {
        if (slot < 0 || slot >= size) return;
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
    }

    protected final void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected final void fillEmpty(ItemStack filler) {
        for (int i = 0; i < size; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler);
        }
    }

    protected final int size() { return size; }

    public final void handle(InventoryClickEvent event) {
        Consumer<MenuClick> action = actions.get(event.getRawSlot());
        if (action == null) return;
        Player player = (Player) event.getWhoClicked();
        Msg.click(player);
        action.accept(new MenuClick(player, event.getClick(), event.getRawSlot(), this));
    }

    public void onClose(Player player) {}

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
