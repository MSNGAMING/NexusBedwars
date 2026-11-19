package dev.nexus.bedwars.shop;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.arena.Game;
import dev.nexus.bedwars.config.Messages;
import dev.nexus.bedwars.gui.ItemBuilder;
import dev.nexus.bedwars.gui.Menu;
import dev.nexus.bedwars.gui.Msg;
import dev.nexus.bedwars.model.TeamColor;
import dev.nexus.bedwars.shop.ShopService.Category;
import dev.nexus.bedwars.shop.ShopService.ShopItem;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Item shop with a per-player Quick-Buy tab. Shift-click any item to pin / unpin it; the layout is
 * persisted asynchronously to the database and restored on the next login.
 */
public final class ShopMenu extends Menu {

    private static final String QUICK = "quick";
    private final Game game;
    private final Player viewer;
    private final TeamColor team;
    private String category;

    public ShopMenu(BedwarsPlugin plugin, Game game, Player viewer, String category) {
        super(plugin, Msg.mm("<dark_gray>Item Shop"), 6);
        this.game = game;
        this.viewer = viewer;
        this.team = game.teamOf(viewer.getUniqueId());
        this.category = category == null ? QUICK : category;
    }

    @Override
    protected void render() {
        ShopService shop = plugin.shop();
        set(1, tab(Material.NETHER_STAR, "Quick Buy", QUICK), c -> switchTo(QUICK));
        int slot = 2;
        for (Category cat : shop.categories()) {
            if (slot > 8) break;
            set(slot++, tab(cat.icon(), cat.name(), cat.id()), c -> switchTo(cat.id()));
        }
        for (int i = 9; i < 18; i++) set(i, ItemBuilder.of(category.equals(QUICK) ? Material.CYAN_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE).name("<dark_gray> ").build());

        List<ShopItem> items = new ArrayList<>();
        if (category.equals(QUICK)) {
            for (String id : plugin.stats().quickBuy(viewer.getUniqueId())) {
                ShopItem it = shop.find(id);
                if (it != null) items.add(it);
            }
        } else {
            for (ShopItem it : shop.items()) if (it.category().equalsIgnoreCase(category)) items.add(it);
        }
        List<String> pinned = plugin.stats().quickBuy(viewer.getUniqueId());
        int pos = 18;
        for (ShopItem it : items) {
            if (pos > 53) break;
            boolean canAfford = ShopService.count(viewer.getInventory(), it.costMaterial()) >= it.cost();
            set(pos++, ItemBuilder.of(it.resolve(team)).amount(it.amount())
                            .name((canAfford ? "<green>" : "<red>") + it.name())
                            .lore("<gray>Cost: <white>" + it.cost() + " " + ShopService.pretty(it.costMaterial()),
                                    canAfford ? "<yellow>Click to purchase" : "<red>You cannot afford this",
                                    pinned.contains(it.id()) ? "<aqua>★ Pinned <dark_gray>(Shift-click to unpin)" : "<dark_gray>Shift-click to pin to Quick Buy").build(),
                    click -> {
                        if (click.shift()) togglePin(it);
                        else buy(click.player(), it);
                    });
        }
        fillEmpty(Msg.filler());
    }

    private ItemStack tab(Material icon, String name, String id) {
        boolean selected = category.equals(id);
        return ItemBuilder.of(icon).name((selected ? "<green><bold>" : "<yellow>") + name).glow(selected).build();
    }

    private void switchTo(String id) {
        category = id;
        rebuild();
    }

    private void togglePin(ShopItem item) {
        UUID id = viewer.getUniqueId();
        List<String> layout = plugin.stats().quickBuy(id);
        if (!layout.remove(item.id())) {
            if (layout.size() >= 36) {
                Msg.error(viewer);
                return;
            }
            layout.add(item.id());
        }
        plugin.stats().saveQuickBuy(id); // async DB write
        rebuild();
    }

    private void buy(Player p, ShopItem item) {
        if (!game.isPlaying(p.getUniqueId())) return;
        PlayerInventory inv = p.getInventory();
        if (!ShopService.charge(inv, item.costMaterial(), item.cost())) {
            plugin.messages().send(p, "cant-afford", Messages.text("amount", item.cost()),
                    Messages.text("item", ShopService.pretty(item.costMaterial())));
            Msg.error(p);
            return;
        }
        Material mat = item.resolve(team);
        if (mat.name().endsWith("_BOOTS")) {
            Material legs = Material.matchMaterial(mat.name().replace("_BOOTS", "_LEGGINGS"));
            game.setArmorTier(p.getUniqueId(), mat);
            ItemStack boots = new ItemStack(mat);
            boots.editMeta(m -> m.setUnbreakable(true));
            inv.setBoots(boots);
            if (legs != null) {
                ItemStack leggingItem = new ItemStack(legs);
                leggingItem.editMeta(m -> m.setUnbreakable(true));
                inv.setLeggings(leggingItem);
            }
        } else {
            if (mat.name().endsWith("_SWORD") && mat != Material.WOODEN_SWORD) inv.remove(Material.WOODEN_SWORD);
            ItemStack bought = new ItemStack(mat, item.amount());
            if (mat.name().endsWith("_SWORD") || mat.name().endsWith("_AXE") || mat.name().endsWith("_PICKAXE") || mat == Material.SHEARS) {
                bought.editMeta(m -> m.setUnbreakable(true));
            }
            inv.addItem(bought).values()
                    .forEach(rest -> p.getWorld().dropItem(p.getLocation(), rest));
        }
        game.applyTeamUpgrades(p, game.team(team));
        plugin.messages().send(p, "purchased", Messages.text("item", item.name()));
        Msg.success(p);
        rebuild();
    }
}
