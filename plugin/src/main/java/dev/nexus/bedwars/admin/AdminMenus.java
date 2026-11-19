package dev.nexus.bedwars.admin;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.gui.ItemBuilder;
import dev.nexus.bedwars.gui.Menu;
import dev.nexus.bedwars.gui.MenuClick;
import dev.nexus.bedwars.gui.Msg;
import dev.nexus.bedwars.gui.PaginatedMenu;
import dev.nexus.bedwars.model.ArenaDefinition;
import dev.nexus.bedwars.model.ValidationReport;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Layer 1 of the triple-layer configuration: the live in-game admin GUI.
 * Every edit is written through {@code ConfigService#set} / the arena model, i.e. the very same
 * store the commands and the YAML files use – nothing here keeps private state.
 */
public final class AdminMenus {

    private AdminMenus() {}

    public static void openHome(BedwarsPlugin plugin, Player player) {
        new Home(plugin).open(player);
    }

    // ═════════════════════════════════════════════════════════════════════════

    static final class Home extends Menu {
        Home(BedwarsPlugin plugin) { super(plugin, Msg.mm("<dark_gray>NexusBedwars <gray>» <yellow>Admin"), 3); }

        @Override
        protected void render() {
            set(11, ItemBuilder.of(Material.GRASS_BLOCK).name("<green><bold>Arenas")
                    .lore("<gray>" + plugin.arenas().definitions().size() + " arena(s)", "",
                            "<yellow>Left-click: <gray>setup wizard", "<yellow>Right-click: <gray>enable / disable").build(),
                    c -> new ArenaList(plugin).open(c.player()));
            set(13, ItemBuilder.of(Material.COMPARATOR).name("<aqua><bold>Live config editor")
                    .lore("<gray>Toggle booleans, adjust numbers,", "<gray>edit text – applied instantly.").build(),
                    c -> new ConfigEditor(plugin).open(c.player()));
            set(15, ItemBuilder.of(Material.REDSTONE).name("<red><bold>Reload from disk")
                    .lore("<gray>Re-read config.yml and arenas.yml.", "<dark_gray>(files are also hot-reloaded automatically)").build(), c -> {
                plugin.config().reload();
                plugin.arenas().reloadFromDisk();
                plugin.messages().send(c.player(), "config-reloaded");
            });
            set(22, ItemBuilder.of(Material.BOOK).name("<gold>NexusBedwars")
                    .lore("<gray>Platform: <white>" + plugin.platform().implementation(),
                            "<gray>Version: <white>" + plugin.getPluginMeta().getVersion()).build());
            fillEmpty(Msg.filler());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════

    static final class ArenaList extends PaginatedMenu<ArenaDefinition> {
        private final BedwarsPlugin plugin;

        ArenaList(BedwarsPlugin plugin) {
            super(plugin, Msg.mm("<dark_gray>Arenas"));
            this.plugin = plugin;
        }

        @Override
        protected List<ArenaDefinition> entries() {
            return plugin.arenas().definitions().stream().sorted(Comparator.comparing(ArenaDefinition::id)).toList();
        }

        @Override
        protected ItemStack icon(ArenaDefinition a) {
            ValidationReport r = ValidationReport.of(a);
            var game = plugin.arenas().game(a.id());
            return ItemBuilder.of(a.enabled() ? Material.LIME_CONCRETE : r.valid() ? Material.YELLOW_CONCRETE : Material.RED_CONCRETE)
                    .name("<white>" + Msg.escape(a.displayName()) + " <dark_gray>(" + a.id() + ")")
                    .lore("<gray>Mode: <white>" + a.modeName(),
                            "<gray>Status: " + (a.enabled() ? "<green>enabled" + (game == null ? "" : " <gray>(" + game.state() + ", " + game.playerCount() + " players)") : "<red>disabled"),
                            "<gray>Checks: <white>" + r.passedCount() + "/" + r.checks().size(), "",
                            "<yellow>Left-click: <gray>open setup wizard",
                            "<yellow>Right-click: <gray>" + (a.enabled() ? "disable" : "enable")).glow(a.enabled()).build();
        }

        @Override
        protected void select(ArenaDefinition a, MenuClick c) {
            if (c.right()) {
                if (a.enabled()) {
                    plugin.arenas().disable(a);
                } else {
                    ValidationReport report = plugin.arenas().enable(a);
                    if (!report.valid()) {
                        plugin.wizard().fail(c.player(), report.failing().size() + " setup check(s) failing – opening wizard");
                        plugin.wizard().open(c.player(), a);
                        return;
                    }
                }
                rebuild();
            } else {
                plugin.wizard().open(c.player(), a);
            }
        }

        @Override protected void back(Player player) { openHome(plugin, player); }

        @Override
        protected void renderHeader() {
            set(4, ItemBuilder.of(Material.PAPER).name("<gold>Create a new arena")
                    .lore("<gray>/bedwars create <id> <solo|doubles|triples|quads>",
                            "<gray>/bedwars create <id> custom <teams> <size>").build());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════

    static final class ConfigEditor extends PaginatedMenu<String> {
        private final BedwarsPlugin plugin;

        ConfigEditor(BedwarsPlugin plugin) {
            super(plugin, Msg.mm("<dark_gray>Live Config Editor"));
            this.plugin = plugin;
        }

        private static boolean editable(String path, Object raw) {
            if (!(raw instanceof Boolean || raw instanceof Number || raw instanceof String)) return false;
            return !(path.startsWith("messages.") || path.startsWith("database.") || path.startsWith("redis.") || path.equals("server-id"));
        }

        @Override
        protected List<String> entries() {
            List<String> out = new ArrayList<>();
            for (String key : plugin.config().keys()) if (editable(key, plugin.config().raw(key))) out.add(key);
            out.sort(String::compareTo);
            return out;
        }

        @Override
        protected ItemStack icon(String path) {
            Object v = plugin.config().raw(path);
            Material m = v instanceof Boolean b ? (b ? Material.LIME_DYE : Material.GRAY_DYE)
                    : v instanceof Number ? Material.REPEATER : Material.NAME_TAG;
            String hint = v instanceof Boolean ? "<yellow>Click: <gray>toggle"
                    : v instanceof Number ? "<yellow>Left/Right: <gray>+ / −   <yellow>Shift: <gray>×10" : "<yellow>Click: <gray>edit in chat";
            return ItemBuilder.of(m).name("<white>" + path).lore("<gray>Value: <aqua>" + Msg.escape(String.valueOf(v)), "", hint).build();
        }

        @Override
        protected void select(String path, MenuClick c) {
            Object v = plugin.config().raw(path);
            if (v instanceof Boolean b) {
                plugin.config().set(path, !b);
            } else if (v instanceof Integer i) {
                int step = c.shift() ? 10 : 1;
                plugin.config().set(path, i + (c.left() || c.shiftLeft() ? step : -step));
            } else if (v instanceof Number n) {
                double step = c.shift() ? 1.0 : 0.1;
                double next = n.doubleValue() + (c.left() || c.shiftLeft() ? step : -step);
                plugin.config().set(path, Math.round(next * 100.0) / 100.0);
            } else {
                plugin.prompts().ask(c.player(), "Type the new value for " + path, text -> {
                    plugin.config().set(path, text);
                    new ConfigEditor(plugin).open(c.player());
                });
                return;
            }
            Msg.success(c.player());
            rebuild();
        }

        @Override protected void back(Player player) { openHome(plugin, player); }
    }
}
