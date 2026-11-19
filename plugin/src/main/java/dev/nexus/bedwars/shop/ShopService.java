package dev.nexus.bedwars.shop;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.model.TeamColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses the hot-reloadable shop layout (config.yml → shop.*) and offers currency helpers. */
public final class ShopService {

    public record ShopItem(String id, String category, String material, int amount, Material costMaterial, int cost, String name) {

        /** Resolves TEAM_* tokens (TEAM_WOOL → RED_WOOL …). */
        public Material resolve(TeamColor team) {
            if (material.startsWith("TEAM_")) return team.material(material.substring(5));
            Material m = Material.matchMaterial(material);
            return m == null ? Material.STONE : m;
        }
    }

    public record Category(String id, Material icon, String name) {}

    private final BedwarsPlugin plugin;
    private volatile List<?> cachedRaw;
    private volatile List<ShopItem> cachedItems = List.of();

    public ShopService(BedwarsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Re-parses only when the underlying config list object changed (hot-reload / GUI edit). */
    public List<ShopItem> items() {
        List<?> raw = plugin.config().list("shop.items");
        if (raw != cachedRaw) {
            List<ShopItem> parsed = new ArrayList<>();
            for (Object o : raw) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Material cost = Material.matchMaterial(String.valueOf(m.get("cost-material")));
                parsed.add(new ShopItem(String.valueOf(m.get("id")), String.valueOf(m.get("category")),
                        String.valueOf(m.get("material")), num(m.get("amount"), 1), cost == null ? Material.IRON_INGOT : cost,
                        num(m.get("cost"), 1), String.valueOf(m.get("name"))));
            }
            cachedItems = List.copyOf(parsed);
            cachedRaw = raw;
        }
        return cachedItems;
    }

    public ShopItem find(String id) {
        for (ShopItem i : items()) if (i.id().equalsIgnoreCase(id)) return i;
        return null;
    }

    public List<Category> categories() {
        List<Category> out = new ArrayList<>();
        for (Object o : plugin.config().list("shop.categories")) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Material icon = Material.matchMaterial(String.valueOf(m.get("icon")));
            out.add(new Category(String.valueOf(m.get("id")), icon == null ? Material.CHEST : icon, String.valueOf(m.get("name"))));
        }
        return out;
    }

    // ── currency helpers (items as currency) ─────────────────────────────────

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    public static int count(PlayerInventory inv, Material mat) {
        int total = 0;
        for (ItemStack it : inv.getStorageContents()) if (it != null && it.getType() == mat) total += it.getAmount();
        ItemStack off = inv.getItemInOffHand();
        if (off != null && off.getType() == mat) total += off.getAmount();
        return total;
    }

    /** @return false (and charges nothing) if the player cannot afford it. */
    public static boolean charge(PlayerInventory inv, Material mat, int amount) {
        if (count(inv, mat) < amount) return false;
        int remaining = amount;
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack it = storage[i];
            if (it != null && it.getType() == mat) {
                int take = Math.min(remaining, it.getAmount());
                it.setAmount(it.getAmount() - take);
                remaining -= take;
                if (it.getAmount() <= 0) storage[i] = null;
            }
        }
        inv.setStorageContents(storage);
        if (remaining > 0) {
            ItemStack off = inv.getItemInOffHand();
            if (off != null && off.getType() == mat) {
                int take = Math.min(remaining, off.getAmount());
                off.setAmount(off.getAmount() - take);
                remaining -= take;
                if (off.getAmount() <= 0) inv.setItemInOffHand(null);
                else inv.setItemInOffHand(off);
            }
        }
        return true;
    }

    public static String pretty(Material m) {
        String n = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
