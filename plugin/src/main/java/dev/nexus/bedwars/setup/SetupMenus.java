package dev.nexus.bedwars.setup;

import dev.nexus.bedwars.gui.ItemBuilder;
import dev.nexus.bedwars.gui.Menu;
import dev.nexus.bedwars.gui.MenuClick;
import dev.nexus.bedwars.gui.Msg;
import dev.nexus.bedwars.gui.PaginatedMenu;
import dev.nexus.bedwars.model.*;
import dev.nexus.bedwars.model.ArenaDefinition.GeneratorDefinition;
import dev.nexus.bedwars.model.ArenaDefinition.TeamDefinition;
import dev.nexus.bedwars.model.ValidationReport.Check;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * All wizard screens. Every screen reads the <em>live</em> {@link ArenaDefinition} on each rebuild,
 * so edits made by commands or by hand-editing arenas.yml show up immediately.
 */
final class SetupMenus {

    private SetupMenus() {}

    static Component title(ArenaDefinition def, String sub) {
        return Msg.mm("<dark_gray>Setup <gray>» <white><n> <gray>» <yellow>" + sub,
                Placeholder.unparsed("n", def.displayName()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Hub – interactive checklist
    // ═════════════════════════════════════════════════════════════════════════

    static final class HubMenu extends Menu {
        private final SetupWizard wiz;
        private final ArenaDefinition def;

        HubMenu(SetupWizard wiz, ArenaDefinition def) {
            super(wiz.plugin(), title(def, "Checklist"), 4);
            this.wiz = wiz;
            this.def = def;
        }

        @Override
        protected void render() {
            ValidationReport report = ValidationReport.of(def);
            SetupStep next = null;
            for (SetupStep s : SetupStep.values()) {
                if (s != SetupStep.REVIEW && !report.stepComplete(s)) { next = s; break; }
            }
            if (next == null) next = SetupStep.REVIEW;

            int total = report.checks().size();
            int ok = (int) report.passedCount();
            int filled = total == 0 ? 0 : Math.round(10f * ok / total);
            String bar = "<green>" + "█".repeat(filled) + "<dark_gray>" + "█".repeat(10 - filled);
            set(4, ItemBuilder.of(Material.BOOK).name("<gold><bold>Setup progress")
                    .lore(bar + " <white>" + ok + "/" + total,
                            "<gray>Mode: <white>" + def.modeName(),
                            "<gray>Status: " + (def.enabled() ? "<green>ENABLED" : "<red>disabled"),
                            "", report.valid() ? "<green>All checks passed – ready to enable!" : "<yellow>" + (total - ok) + " check(s) still failing").build());

            int slot = 10;
            for (SetupStep step : SetupStep.values()) {
                boolean done = step == SetupStep.REVIEW ? report.valid() : report.stepComplete(step);
                List<String> lore = new ArrayList<>();
                lore.add("<gray>" + step.description());
                lore.add("");
                int shown = 0;
                for (Check c : report.checks()) {
                    if (c.step() != step || shown++ >= 6) continue;
                    lore.add((c.passed() ? "<green>✔ " : "<red>✘ ") + "<gray>" + c.name());
                }
                if (step == next) lore.add("<yellow>▶ Recommended next step");
                lore.add("<dark_gray>Click to open");
                set(slot++, ItemBuilder.of(step.icon())
                        .name((done ? "<green>✔ " : "<red>✘ ") + "<white>" + (step.ordinal() + 1) + ". " + step.title())
                        .lore(lore).glow(step == next).build(), c -> wiz.openStep(c.player(), def, step));
            }
            set(30, ItemBuilder.of(Material.CHEST).name("<yellow><bold>Equip Setup Hotbar")
                    .lore("<gray>Equips the 9-slot in-game toolkit",
                            "<gray>to configure all points on the fly.",
                            "",
                            "<yellow>Click to equip tools").glow(true).build(), c -> {
                c.player().closeInventory();
                wiz.enter(c.player(), def);
            });
            set(31, ItemBuilder.of(Material.BARRIER).name("<red>Close").build(), c -> c.player().closeInventory());
            fillEmpty(Msg.filler());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Step base class (footer navigation + coordinate "point" buttons)
    // ═════════════════════════════════════════════════════════════════════════

    abstract static class StepMenu extends Menu {
        protected final SetupWizard wiz;
        protected final ArenaDefinition def;
        protected final SetupStep step;
        private final int rows;

        StepMenu(SetupWizard wiz, ArenaDefinition def, SetupStep step, int rows) {
            super(wiz.plugin(), title(def, step.title()), rows);
            this.wiz = wiz;
            this.def = def;
            this.step = step;
            this.rows = rows;
        }

        protected abstract void renderStep();

        protected void back(Player player) { wiz.openHub(player, def); }

        protected SetupStep nextStep() { return step.next(); }

        @Override
        protected final void render() {
            renderStep();
            int base = (rows - 1) * 9;
            set(base, ItemBuilder.of(Material.ARROW).name("<yellow>« Back").build(), c -> back(c.player()));
            ValidationReport report = ValidationReport.of(def);
            boolean done = report.stepComplete(step);
            set(base + 4, ItemBuilder.of(done ? Material.LIME_DYE : Material.RED_DYE)
                    .name(done ? "<green>Step complete" : "<red>Step incomplete")
                    .lore(report.checks().stream().filter(c -> c.step() == step && !c.passed())
                            .map(c -> "<red>✘ <gray>" + c.name() + " <dark_gray>(" + c.detail() + ")").limit(6).toList()).build());
            SetupStep next = nextStep();
            if (next != null) {
                set(base + 8, ItemBuilder.of(Material.LIME_CONCRETE).name("<green>Next: " + next.title() + " »").build(),
                        c -> wiz.openStep(c.player(), def, next));
            }
            fillEmpty(Msg.filler());
        }

        /** A standard "location" button: left = set, right = teleport, shift+right = clear. */
        protected final void point(int slot, String label, Material icon, String value, Consumer<Player> setHere,
                                   Supplier<Location> teleportTarget, Runnable clear) {
            boolean isSet = value != null;
            set(slot, ItemBuilder.of(icon).name((isSet ? "<green>✔ " : "<red>✘ ") + "<white>" + label)
                    .lore("<gray>Current: <white>" + (isSet ? value : "not set"), "",
                            "<yellow>Left-click: <gray>set to your position",
                            "<yellow>Right-click: <gray>teleport there",
                            "<yellow>Shift+Right: <gray>clear").glow(isSet).build(), click -> {
                Player p = click.player();
                if (click.shiftRight()) {
                    clear.run();
                    wiz.changed(p, def, label + " cleared");
                } else if (click.right()) {
                    Location target = teleportTarget.get();
                    if (target == null) wiz.fail(p, label + " is not set (or its world is not loaded)");
                    else wiz.plugin().scheduler().teleport(p, target);
                } else if (click.left()) {
                    setHere.accept(p);
                }
                rebuild();
            });
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Step 1 – region
    // ═════════════════════════════════════════════════════════════════════════

    static final class RegionMenu extends StepMenu {
        RegionMenu(SetupWizard wiz, ArenaDefinition def) { super(wiz, def, SetupStep.REGION, 4); }

        @Override
        protected void renderStep() {
            corner(10, 1, def.pos1());
            corner(12, 2, def.pos2());
            set(14, ItemBuilder.of(Material.GOLDEN_AXE).name("<gold>Get selection wand")
                    .lore("<gray>Left-click a block → pos 1", "<gray>Right-click a block → pos 2", "", "<yellow>Click to receive the wand").glow(true).build(), c -> {
                wiz.giveWand(c.player());
                wiz.changed(c.player(), def, "Wand added to your inventory");
            });
            String dims = def.hasRegion()
                    ? (def.max().x() - def.min().x() + 1) + " × " + (def.max().y() - def.min().y() + 1) + " × " + (def.max().z() - def.min().z() + 1)
                    : "–";
            set(16, ItemBuilder.of(Material.MAP).name("<aqua>Region summary")
                    .lore("<gray>World: <white>" + (def.world() == null ? "–" : def.world()), "<gray>Size: <white>" + dims,
                            "", "<yellow>Shift+Click: <gray>clear selection").build(), c -> {
                if (c.shift()) {
                    def.pos1(null);
                    def.pos2(null);
                    wiz.changed(c.player(), def, "Selection cleared");
                    rebuild();
                }
            });
        }

        private void corner(int slot, int n, BlockPos pos) {
            boolean setNow = pos != null;
            set(slot, ItemBuilder.of(setNow ? Material.LIME_CONCRETE : Material.RED_CONCRETE)
                    .name((setNow ? "<green>✔ " : "<red>✘ ") + "<white>Position " + n)
                    .lore("<gray>Current: <white>" + (setNow ? pos.pretty() : "not set"), "",
                            "<yellow>Left-click: <gray>use the block you stand in",
                            "<yellow>Right-click: <gray>use the block you look at").build(), click -> {
                Player p = click.player();
                if (click.right()) {
                    Block target = p.getTargetBlockExact(30);
                    if (target == null) { wiz.fail(p, "Look at a block first"); return; }
                    wiz.setCorner(p, def, n, BlockPos.of(target));
                } else {
                    wiz.setCorner(p, def, n, BlockPos.of(p.getLocation()));
                }
                rebuild();
            });
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Steps 2 & 3 – single location (lobby / spectator)
    // ═════════════════════════════════════════════════════════════════════════

    static final class LocationMenu extends StepMenu {
        private final String label;
        private final Material icon;
        private final Supplier<LocationData> getter;
        private final Consumer<LocationData> setter;

        LocationMenu(SetupWizard wiz, ArenaDefinition def, SetupStep step, String label, Material icon,
                     Supplier<LocationData> getter, Consumer<LocationData> setter) {
            super(wiz, def, step, 4);
            this.label = label;
            this.icon = icon;
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        protected void renderStep() {
            LocationData current = getter.get();
            point(13, label, icon, current == null ? null : current.pretty(), p -> {
                setter.accept(LocationData.of(p.getLocation()));
                wiz.changed(p, def, label + " set");
            }, () -> getter.get() == null ? null : getter.get().toLocation(), () -> setter.accept(null));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Step 4 – teams
    // ═════════════════════════════════════════════════════════════════════════

    static final class TeamsMenu extends StepMenu {
        TeamsMenu(SetupWizard wiz, ArenaDefinition def) { super(wiz, def, SetupStep.TEAMS, 4); }

        @Override
        protected void renderStep() {
            int slot = 9;
            for (TeamColor color : def.activeColors()) {
                TeamDefinition t = def.team(color);
                set(slot++, ItemBuilder.of(color.wool()).name("<white>Team <" + color.text().asHexString() + ">" + color.displayName())
                        .lore(mark(t.spawn() != null, "Spawn"), mark(t.bed() != null, "Bed"),
                                mark(t.shopNpc() != null, "Shop NPC"), mark(t.upgradeNpc() != null, "Upgrade NPC"),
                                "", "<yellow>Click to edit").glow(t.isComplete()).build(),
                        c -> new TeamEditMenu(wiz, def, color).open(c.player()));
            }
            set(22, ItemBuilder.of(Material.PAPER).name("<gray>Teams in this mode: <white>" + def.teamCount())
                    .lore("<dark_gray>Change the team count in the Settings step").build());
        }

        private static String mark(boolean ok, String name) {
            return (ok ? "<green>✔ " : "<red>✘ ") + "<gray>" + name;
        }
    }

    static final class TeamEditMenu extends StepMenu {
        private final TeamColor color;

        TeamEditMenu(SetupWizard wiz, ArenaDefinition def, TeamColor color) {
            super(wiz, def, SetupStep.TEAMS, 4);
            this.color = color;
        }

        @Override
        protected void back(Player player) { wiz.openStep(player, def, SetupStep.TEAMS); }

        @Override
        protected SetupStep nextStep() { return null; }

        @Override
        protected void renderStep() {
            TeamDefinition t = def.team(color);
            String name = color.displayName();
            point(10, name + " spawn", Material.COMPASS, fmt(t.spawn()), p -> {
                t.spawn(LocationData.of(p.getLocation()));
                wiz.changed(p, def, name + " spawn set");
            }, () -> t.spawn() == null ? null : t.spawn().toLocation(), () -> t.spawn(null));

            point(12, name + " bed", color.material("BED"), t.bed() == null ? null : t.bed().pretty(), p -> {
                Block target = p.getTargetBlockExact(10);
                if (target == null || !Tag.BEDS.isTagged(target.getType())) {
                    wiz.fail(p, "Look at the team's bed block (within 10 blocks)");
                    return;
                }
                t.bed(BlockPos.of(target));
                wiz.changed(p, def, name + " bed set");
            }, () -> t.bed() == null ? null : new Location(org.bukkit.Bukkit.getWorld(def.world() == null ? "" : def.world()),
                    t.bed().x() + 0.5, t.bed().y() + 1, t.bed().z() + 0.5), () -> t.bed(null));

            point(14, name + " shop NPC", Material.VILLAGER_SPAWN_EGG, fmt(t.shopNpc()), p -> {
                t.shopNpc(LocationData.centered(p.getLocation()));
                wiz.changed(p, def, name + " shop NPC set");
            }, () -> t.shopNpc() == null ? null : t.shopNpc().toLocation(), () -> t.shopNpc(null));

            point(16, name + " upgrade NPC", Material.ENCHANTING_TABLE, fmt(t.upgradeNpc()), p -> {
                t.upgradeNpc(LocationData.centered(p.getLocation()));
                wiz.changed(p, def, name + " upgrade NPC set");
            }, () -> t.upgradeNpc() == null ? null : t.upgradeNpc().toLocation(), () -> t.upgradeNpc(null));
        }

        private static String fmt(LocationData l) { return l == null ? null : l.pretty(); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Step 5 – generators (paginated)
    // ═════════════════════════════════════════════════════════════════════════

    static final class GeneratorsMenu extends PaginatedMenu<GeneratorDefinition> {
        private static final double[] SPEEDS = {0.5, 0.75, 1.0, 1.25, 1.5, 2.0};
        private final SetupWizard wiz;
        private final ArenaDefinition def;

        GeneratorsMenu(SetupWizard wiz, ArenaDefinition def) {
            super(wiz.plugin(), title(def, "Generators"));
            this.wiz = wiz;
            this.def = def;
        }

        @Override protected List<GeneratorDefinition> entries() { return new ArrayList<>(def.generators()); }

        @Override
        protected ItemStack icon(GeneratorDefinition g) {
            return ItemBuilder.of(g.type().material()).name("<white>" + g.type().name() + " <dark_gray>#" + g.id())
                    .lore("<gray>At: <white>" + g.location().pretty(),
                            "<gray>Owner: <white>" + (g.owner() == null ? "public" : g.owner().displayName()),
                            "<gray>Split drop: " + (g.splitDrop() ? "<green>on" : "<red>off"),
                            "<gray>Speed: <white>×" + g.speedMultiplier(), "",
                            "<yellow>Left: <gray>teleport   <yellow>Right: <gray>toggle split",
                            "<yellow>Shift+Left: <gray>cycle speed   <yellow>Shift+Right: <gray>remove").build();
        }

        @Override
        protected void select(GeneratorDefinition g, MenuClick c) {
            Player p = c.player();
            if (c.shiftRight()) {
                def.removeGenerator(g);
                wiz.changed(p, def, "Generator removed");
            } else if (c.shiftLeft()) {
                double next = SPEEDS[0];
                for (double s : SPEEDS) if (s > g.speedMultiplier() + 1e-9) { next = s; break; }
                g.speedMultiplier(next);
                wiz.changed(p, def, "Speed multiplier ×" + next);
            } else if (c.right()) {
                g.splitDrop(!g.splitDrop());
                wiz.changed(p, def, "Split drop " + (g.splitDrop() ? "enabled" : "disabled"));
            } else if (c.left()) {
                Location loc = g.location().toLocation();
                if (loc != null) wiz.plugin().scheduler().teleport(p, loc);
                return;
            }
            rebuild();
        }

        @Override protected void back(Player player) { wiz.openHub(player, def); }

        @Override
        protected void renderHeader() {
            int slot = 2;
            for (GeneratorType type : GeneratorType.values()) {
                set(slot++, ItemBuilder.of(type.material()).name("<green>+ Add " + type.name() + " generator")
                        .lore("<gray>Places it at your feet.",
                                type.isTeamBase() ? "<gray>Team owner is auto-detected from the nearest team spawn." : "<gray>Public generator (middle of the map).")
                        .build(), c -> {
                    Player p = c.player();
                    LocationData at = LocationData.centered(p.getLocation());
                    TeamColor owner = detectOwner(type, at);
                    def.addGenerator(type, at, owner);
                    wiz.changed(p, def, type.name() + " generator added" + (owner != null ? " for " + owner.displayName() : ""));
                    rebuild();
                });
            }
            SetupStep next = SetupStep.GENERATORS.next();
            set(7, ItemBuilder.of(Material.LIME_CONCRETE).name("<green>Next: " + next.title() + " »").build(),
                    c -> wiz.openStep(c.player(), def, next));
        }

        private TeamColor detectOwner(GeneratorType type, LocationData at) {
            if (!type.isTeamBase()) return null;
            TeamColor best = null;
            double bestDist = 12 * 12;
            for (TeamColor color : def.activeColors()) {
                TeamDefinition t = def.teamIfPresent(color);
                if (t == null || t.spawn() == null) continue;
                double d = t.spawn().distanceSquared(at.x(), at.y(), at.z());
                if (d < bestDist) { bestDist = d; best = color; }
            }
            return best;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Step 6 – settings
    // ═════════════════════════════════════════════════════════════════════════

    static final class SettingsMenu extends StepMenu {
        private static final int[][] PRESETS = {{8, 1}, {8, 2}, {4, 3}, {4, 4}};

        SettingsMenu(SetupWizard wiz, ArenaDefinition def) { super(wiz, def, SetupStep.SETTINGS, 4); }

        @Override
        protected void renderStep() {
            set(10, ItemBuilder.of(Material.NAME_TAG).name("<white>Display name")
                    .lore("<gray>Current: <white>" + Msg.escape(def.displayName()), "", "<yellow>Click to change (type in chat)").build(),
                    c -> wiz.plugin().prompts().ask(c.player(), "Type the new display name", text -> {
                        def.displayName(text);
                        wiz.changed(c.player(), def, "Display name set to " + text);
                        wiz.openStep(c.player(), def, SetupStep.SETTINGS);
                    }));

            stepper(11, Material.WHITE_BANNER, "Team count", def.teamCount(), "2–8", n -> def.teamCount(def.teamCount() + n));
            stepper(12, Material.PLAYER_HEAD, "Players per team", def.teamSize(), "1–16", n -> def.teamSize(def.teamSize() + n));
            stepper(13, Material.CLOCK, "Minimum players", def.minPlayers(), "2–" + def.maxPlayers(), n -> def.minPlayers(def.minPlayers() + n));

            set(14, ItemBuilder.of(Material.COMPARATOR).name("<aqua>Mode: <white>" + def.modeName())
                    .lore("<gray>Max players: <white>" + def.maxPlayers(), "", "<yellow>Click: <gray>cycle presets",
                            "<dark_gray>Solo → Doubles → 3v3v3v3 → 4v4v4v4").build(), c -> {
                int idx = -1;
                for (int i = 0; i < PRESETS.length; i++) {
                    if (PRESETS[i][0] == def.teamCount() && PRESETS[i][1] == def.teamSize()) idx = i;
                }
                int[] next = PRESETS[(idx + 1) % PRESETS.length];
                def.teamCount(next[0]);
                def.teamSize(next[1]);
                def.minPlayers(def.minPlayers());
                wiz.changed(c.player(), def, "Mode set to " + def.modeName());
                rebuild();
            });
            set(16, ItemBuilder.of(Material.GRASS_BLOCK).name("<white>World")
                    .lore("<gray>" + (def.world() == null ? "set by the region step" : def.world())).build());
        }

        private void stepper(int slot, Material icon, String label, int value, String range, Consumer<Integer> change) {
            set(slot, ItemBuilder.of(icon).name("<white>" + label + ": <yellow>" + value)
                    .lore("<gray>Range: " + range, "", "<yellow>Left-click: <green>+1   <yellow>Right-click: <red>−1",
                            "<yellow>Shift: <gray>×5").amount(Math.max(1, value)).build(), c -> {
                int step = c.shift() ? 5 : 1;
                change.accept(c.left() || c.shiftLeft() ? step : -step);
                wiz.changed(c.player(), def, label + " → " + def.modeName());
                rebuild();
            });
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Step 7 – review & enable
    // ═════════════════════════════════════════════════════════════════════════

    static final class ReviewMenu extends PaginatedMenu<Check> {
        private final SetupWizard wiz;
        private final ArenaDefinition def;

        ReviewMenu(SetupWizard wiz, ArenaDefinition def) {
            super(wiz.plugin(), title(def, "Review"));
            this.wiz = wiz;
            this.def = def;
        }

        @Override protected List<Check> entries() { return ValidationReport.of(def).checks(); }

        @Override
        protected ItemStack icon(Check c) {
            return ItemBuilder.of(c.passed() ? Material.LIME_CONCRETE : Material.RED_CONCRETE)
                    .name((c.passed() ? "<green>✔ " : "<red>✘ ") + "<white>" + c.name())
                    .lore("<gray>" + c.detail(), "", c.passed() ? "<dark_gray>Click to review the step" : "<yellow>Click to fix this step").build();
        }

        @Override protected void select(Check c, MenuClick click) { wiz.openStep(click.player(), def, c.step()); }

        @Override protected void back(Player player) { wiz.openHub(player, def); }

        @Override
        protected void renderHeader() {
            ValidationReport report = ValidationReport.of(def);
            if (def.enabled()) {
                set(4, ItemBuilder.of(Material.REDSTONE_BLOCK).name("<red><bold>Disable arena")
                        .lore("<gray>Kicks all players and stops the game.").build(), c -> {
                    wiz.plugin().arenas().disable(def);
                    wiz.changed(c.player(), def, "Arena disabled");
                    rebuild();
                });
            } else if (report.valid()) {
                set(4, ItemBuilder.of(Material.EMERALD_BLOCK).name("<green><bold>Enable arena")
                        .lore("<gray>All checks passed.", "<yellow>Click to open the arena for players!").glow(true).build(), c -> {
                    wiz.plugin().arenas().enable(def);
                    wiz.changed(c.player(), def, "Arena enabled");
                    rebuild();
                });
            } else {
                set(4, ItemBuilder.of(Material.BARRIER).name("<red><bold>Cannot enable yet")
                        .lore("<gray>" + report.failing().size() + " check(s) failing.", "<gray>Click a red entry to jump to its step.").build());
            }
        }
    }
}
