package dev.nexus.bedwars.setup;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.gui.ItemBuilder;
import dev.nexus.bedwars.gui.Menu;
import dev.nexus.bedwars.gui.Msg;
import dev.nexus.bedwars.model.*;
import dev.nexus.bedwars.model.ArenaDefinition.GeneratorDefinition;
import dev.nexus.bedwars.model.ArenaDefinition.TeamDefinition;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Step-by-step arena setup wizard and interactive in-game hotbar toolkit.
 *
 * <p>Admins entering setup mode via {@code /bw setup <arena>} receive a 9-slot toolkit in their
 * hotbar, while their original inventory and gamemode are snapshotted and safely restored upon exit.
 * All mutations write directly into the live {@link ArenaDefinition} and re-render dynamic tool
 * states (active team colors, set coordinates, generator counts, and validation status).
 */
public final class SetupWizard implements Listener {

    public record InventorySnapshot(ItemStack[] contents, ItemStack[] armor, ItemStack offhand, GameMode mode) {}

    private final BedwarsPlugin plugin;
    private final NamespacedKey toolKey;
    private final NamespacedKey wandKey;

    private final Map<UUID, String> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, InventorySnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> teamIndices = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActionTimes = new ConcurrentHashMap<>();

    public SetupWizard(BedwarsPlugin plugin) {
        this.plugin = plugin;
        this.toolKey = new NamespacedKey(plugin, "setup_tool");
        this.wandKey = new NamespacedKey(plugin, "setup_wand");
    }

    public BedwarsPlugin plugin() { return plugin; }

    public boolean isInSetup(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public String sessionArena(Player player) {
        return sessions.get(player.getUniqueId());
    }

    // ── session lifecycle ────────────────────────────────────────────────────

    /**
     * Enters interactive setup mode: snapshots inventory and equips the 9-slot toolkit.
     */
    public void enter(Player player, ArenaDefinition def) {
        plugin.scheduler().ensureEntity(player, () -> {
            UUID uuid = player.getUniqueId();
            sessions.put(uuid, def.id());
            teamIndices.putIfAbsent(uuid, 0);

            if (!snapshots.containsKey(uuid)) {
                ItemStack[] contents = cloneItemArray(player.getInventory().getContents());
                ItemStack[] armor = cloneItemArray(player.getInventory().getArmorContents());
                ItemStack offhand = player.getInventory().getItemInOffHand().clone();
                snapshots.put(uuid, new InventorySnapshot(contents, armor, offhand, player.getGameMode()));
            }

            player.setGameMode(GameMode.CREATIVE);
            player.getInventory().clear();
            player.getInventory().setItemInOffHand(null);
            equipHotbar(player, def);

            player.sendMessage(Msg.mm("<gold>═════════════════════════════════════════════════════════════"));
            player.sendMessage(Msg.mm("<yellow><bold>NexusBedwars Setup Mode <gray>– <aqua>" + def.id() + " <gray>(" + def.modeName() + ")"));
            player.sendMessage(Msg.mm("<gray>Your hotbar has been equipped with setup tools:"));
            player.sendMessage(Msg.mm(" <yellow>[1] Wand <gray>– Pos 1 / Pos 2"));
            player.sendMessage(Msg.mm(" <yellow>[2] Spawns <gray>– Waiting Lobby (Left) / Spectator (Right)"));
            player.sendMessage(Msg.mm(" <yellow>[3] Team Base <gray>– Cycle (Shift-Click), Spawn (Left), Bed (Right)"));
            player.sendMessage(Msg.mm(" <yellow>[4] Team NPC <gray>– Shop NPC (Left), Upgrade NPC (Right)"));
            player.sendMessage(Msg.mm(" <yellow>[5] Base Gen <gray>– Iron Gen (Left), Gold Gen (Right)"));
            player.sendMessage(Msg.mm(" <yellow>[6] Tier Gen <gray>– Diamond Gen (Left), Emerald Gen (Right)"));
            player.sendMessage(Msg.mm(" <yellow>[7] Remover <gray>– Click NPC or near Gen to remove"));
            player.sendMessage(Msg.mm(" <yellow>[8] Checklist <gray>– Open Setup GUI"));
            player.sendMessage(Msg.mm(" <yellow>[9] Save & Exit <gray>– Exit setup mode"));
            player.sendMessage(Msg.mm("<gold>═════════════════════════════════════════════════════════════"));
            player.sendActionBar(Msg.mm("<green>Equipped Setup Toolkit for <white>" + def.id()));
            Msg.success(player);
        });
    }

    /** Compatibility alias for /bedwars setup <arena>. */
    public void open(Player player, ArenaDefinition def) {
        enter(player, def);
    }

    /**
     * Exits setup mode and restores player inventory, armor, offhand, and gamemode.
     */
    public void exit(Player player) {
        plugin.scheduler().ensureEntity(player, () -> {
            UUID uuid = player.getUniqueId();
            String arenaId = sessions.remove(uuid);
            teamIndices.remove(uuid);
            lastActionTimes.remove(uuid);
            InventorySnapshot snapshot = snapshots.remove(uuid);

            player.getInventory().clear();
            player.getInventory().setItemInOffHand(null);

            if (snapshot != null) {
                if (snapshot.contents() != null) player.getInventory().setContents(snapshot.contents());
                if (snapshot.armor() != null) player.getInventory().setArmorContents(snapshot.armor());
                if (snapshot.offhand() != null) player.getInventory().setItemInOffHand(snapshot.offhand());
                if (snapshot.mode() != null) player.setGameMode(snapshot.mode());
            }

            player.sendMessage(Msg.mm("<yellow>Exited setup mode. Inventory restored."));
            if (arenaId != null) {
                ArenaDefinition def = plugin.arenas().definition(arenaId).orElse(null);
                if (def != null) {
                    plugin.arenas().onDefinitionChanged(def);
                    ValidationReport report = ValidationReport.of(def);
                    if (report.valid()) {
                        player.sendMessage(Msg.mm("<green><bold>✔ Arena '" + def.id() + "' is fully configured and ready! Use /bw enable " + def.id() + " to enable it."));
                    } else {
                        player.sendMessage(Msg.mm("<yellow>Arena '" + def.id() + "' saved with " + report.failing().size() + " incomplete checks. Run /bw setup " + def.id() + " anytime to continue."));
                    }
                }
            }
            Msg.success(player);
        });
    }

    /**
     * Server shutdown/disable cleanup: restores all player inventories cleanly.
     */
    public void shutdown() {
        for (Map.Entry<UUID, InventorySnapshot> entry : snapshots.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                InventorySnapshot snapshot = entry.getValue();
                player.getInventory().clear();
                player.getInventory().setItemInOffHand(null);
                if (snapshot.contents() != null) player.getInventory().setContents(snapshot.contents());
                if (snapshot.armor() != null) player.getInventory().setArmorContents(snapshot.armor());
                if (snapshot.offhand() != null) player.getInventory().setItemInOffHand(snapshot.offhand());
                if (snapshot.mode() != null) player.setGameMode(snapshot.mode());
                player.sendMessage(Msg.mm("<yellow>NexusBedwars: Exited setup mode due to reload/shutdown. Inventory restored."));
            }
        }
        snapshots.clear();
        sessions.clear();
        teamIndices.clear();
        lastActionTimes.clear();
    }

    // ── hotbar toolkit rendering ─────────────────────────────────────────────

    public void equipHotbar(Player player, ArenaDefinition def) {
        plugin.scheduler().ensureEntity(player, () -> {
            if (!sessions.containsKey(player.getUniqueId())) return;

            // Slot 0: Selection Wand
            ItemStack wand = ItemBuilder.of(Material.GOLDEN_AXE)
                    .name("<gold><bold>Selection Wand")
                    .lore("<yellow>Left-Click Block <gray>→ <white>Set Position 1",
                            "<yellow>Right-Click Block <gray>→ <white>Set Position 2",
                            "",
                            "<gray>Defines the bounding box of the arena.",
                            "<dark_gray>Pos 1: " + (def.pos1() == null ? "<red>Not set" : "<green>" + def.pos1().pretty()),
                            "<dark_gray>Pos 2: " + (def.pos2() == null ? "<red>Not set" : "<green>" + def.pos2().pretty()))
                    .tag(toolKey, "wand")
                    .glow(def.hasRegion())
                    .build();
            player.getInventory().setItem(0, wand);

            // Slot 1: Spawns Tool
            ItemStack spawns = ItemBuilder.of(Material.RECOVERY_COMPASS)
                    .name("<aqua><bold>Spawns Tool")
                    .lore("<yellow>Left-Click <gray>→ <white>Set Waiting Lobby <gray>(at feet)",
                            "<yellow>Right-Click <gray>→ <white>Set Spectator Spawn <gray>(at feet)",
                            "",
                            "<gray>Captures current position, yaw & pitch.",
                            "<dark_gray>Lobby: " + (def.waitingLobby() == null ? "<red>Not set" : "<green>" + def.waitingLobby().pretty()),
                            "<dark_gray>Spectator: " + (def.spectatorSpawn() == null ? "<red>Not set" : "<green>" + def.spectatorSpawn().pretty()))
                    .tag(toolKey, "spawns")
                    .glow(def.waitingLobby() != null && def.spectatorSpawn() != null)
                    .build();
            player.getInventory().setItem(1, spawns);

            // Slots 2 & 3: Team Tools
            List<TeamColor> active = def.activeColors();
            int idx = active.isEmpty() ? 0 : Math.floorMod(teamIndices.getOrDefault(player.getUniqueId(), 0), active.size());
            TeamColor current = active.isEmpty() ? TeamColor.RED : active.get(idx);
            TeamColor next = active.isEmpty() ? TeamColor.RED : active.get((idx + 1) % active.size());
            TeamDefinition t = def.team(current);

            String spawnStr = t.spawn() == null ? "<red>✘ Not set" : "<green>✔ Set";
            String bedStr = t.bed() == null ? "<red>✘ Not set" : "<green>✔ Set (" + t.bed().pretty() + ")";
            ItemStack teamBase = ItemBuilder.of(current.wool())
                    .name("<white>Team Base Tool <gray>– <" + current.text().asHexString() + "><bold>" + current.displayName())
                    .lore("<yellow>Shift + Click <gray>→ <aqua>Cycle Team <dark_gray>(Next: " + next.displayName() + ")",
                            "<yellow>Left-Click <gray>→ <white>Set Team Spawn <gray>(at feet)",
                            "<yellow>Right-Click Bed <gray>→ <white>Set Team Bed <gray>(look at bed block)",
                            "",
                            "<gray>Team Status (" + current.displayName() + "):",
                            " <gray>Spawn: " + spawnStr,
                            " <gray>Bed: " + bedStr)
                    .tag(toolKey, "team_base")
                    .glow(t.spawn() != null && t.bed() != null)
                    .build();
            player.getInventory().setItem(2, teamBase);

            String shopStr = t.shopNpc() == null ? "<red>✘ Not set" : "<green>✔ Set";
            String upgStr = t.upgradeNpc() == null ? "<red>✘ Not set" : "<green>✔ Set";
            ItemStack teamNpc = ItemBuilder.of(Material.VILLAGER_SPAWN_EGG)
                    .name("<gold><bold>Team NPC Tool <gray>– <" + current.text().asHexString() + "><bold>" + current.displayName())
                    .lore("<yellow>Shift + Click <gray>→ <aqua>Cycle Team <dark_gray>(Next: " + next.displayName() + ")",
                            "<yellow>Left-Click <gray>→ <white>Set Shop NPC <gray>(at feet)",
                            "<yellow>Right-Click <gray>→ <white>Set Upgrade NPC <gray>(at feet)",
                            "",
                            "<gray>Team Status (" + current.displayName() + "):",
                            " <gray>Shop NPC: " + shopStr,
                            " <gray>Upgrade NPC: " + upgStr)
                    .tag(toolKey, "team_npc")
                    .glow(t.shopNpc() != null && t.upgradeNpc() != null)
                    .build();
            player.getInventory().setItem(3, teamNpc);

            // Slot 4: Base Generator Tool
            long ironCount = def.generators().stream().filter(g -> g.type() == GeneratorType.IRON).count();
            long goldCount = def.generators().stream().filter(g -> g.type() == GeneratorType.GOLD).count();
            ItemStack genBase = ItemBuilder.of(Material.IRON_BLOCK)
                    .name("<white><bold>Base Generator Tool")
                    .lore("<yellow>Left-Click <gray>→ <white>Add Iron Generator <gray>(at feet)",
                            "<yellow>Right-Click <gray>→ <gold>Add Gold Generator <gray>(at feet)",
                            "",
                            "<gray>Auto-attaches to nearest team spawn.",
                            "<dark_gray>Current: <white>" + ironCount + " Iron<gray>, <gold>" + goldCount + " Gold")
                    .tag(toolKey, "gen_base")
                    .build();
            player.getInventory().setItem(4, genBase);

            // Slot 5: Tier Generator Tool
            long diaCount = def.generators().stream().filter(g -> g.type() == GeneratorType.DIAMOND).count();
            long emeCount = def.generators().stream().filter(g -> g.type() == GeneratorType.EMERALD).count();
            ItemStack genTier = ItemBuilder.of(Material.DIAMOND_BLOCK)
                    .name("<aqua><bold>Tier Generator Tool")
                    .lore("<yellow>Left-Click <gray>→ <aqua>Add Diamond Generator <gray>(at feet)",
                            "<yellow>Right-Click <gray>→ <green>Add Emerald Generator <gray>(at feet)",
                            "",
                            "<gray>Places public map generators.",
                            "<dark_gray>Current: <aqua>" + diaCount + " Diamond<gray>, <green>" + emeCount + " Emerald")
                    .tag(toolKey, "gen_tier")
                    .build();
            player.getInventory().setItem(5, genTier);

            // Slot 6: Remover / Undo Tool
            ItemStack remover = ItemBuilder.of(Material.SHEARS)
                    .name("<red><bold>Remover / Undo Tool")
                    .lore("<yellow>Click NPC <gray>→ <white>Remove Shop / Upgrade NPC",
                            "<yellow>Click near Gen <gray>→ <white>Remove nearest Generator",
                            "",
                            "<gray>Interact near any generator or NPC to delete it.")
                    .tag(toolKey, "remover")
                    .build();
            player.getInventory().setItem(6, remover);

            // Slot 7: Checklist & Review Menu
            ValidationReport report = ValidationReport.of(def);
            ItemStack menu = ItemBuilder.of(Material.BOOK)
                    .name("<yellow><bold>Checklist & GUI Menu")
                    .lore("<yellow>Right-Click <gray>→ <white>Open Setup Checklist GUI",
                            "",
                            "<gray>Progress: <white>" + report.passedCount() + "/" + report.checks().size() + " passed",
                            report.valid() ? "<green>✔ Ready to enable!" : "<red>✘ Incomplete")
                    .tag(toolKey, "menu")
                    .glow(report.valid())
                    .build();
            player.getInventory().setItem(7, menu);

            // Slot 8: Save & Exit Setup
            ItemStack exit = ItemBuilder.of(Material.BARRIER)
                    .name("<red><bold>Save & Exit Setup")
                    .lore("<yellow>Right-Click <gray>→ <white>Exit Setup Mode",
                            "",
                            "<gray>Restores your inventory & gamemode.",
                            "<gray>Edits are automatically saved.")
                    .tag(toolKey, "exit")
                    .build();
            player.getInventory().setItem(8, exit);
        });
    }

    // ── navigation ───────────────────────────────────────────────────────────

    public void openHub(Player player, ArenaDefinition def) {
        new SetupMenus.HubMenu(this, def).open(player);
    }

    public void openStep(Player player, ArenaDefinition def, SetupStep step) {
        switch (step) {
            case REGION -> new SetupMenus.RegionMenu(this, def).open(player);
            case LOBBY -> new SetupMenus.LocationMenu(this, def, SetupStep.LOBBY, "Waiting lobby spawn", Material.OAK_DOOR,
                    def::waitingLobby, def::waitingLobby).open(player);
            case SPECTATOR -> new SetupMenus.LocationMenu(this, def, SetupStep.SPECTATOR, "Spectator spawn", Material.ENDER_EYE,
                    def::spectatorSpawn, def::spectatorSpawn).open(player);
            case TEAMS -> new SetupMenus.TeamsMenu(this, def).open(player);
            case GENERATORS -> new SetupMenus.GeneratorsMenu(this, def).open(player);
            case SETTINGS -> new SetupMenus.SettingsMenu(this, def).open(player);
            case REVIEW -> new SetupMenus.ReviewMenu(this, def).open(player);
        }
    }

    // ── shared feedback / persistence ────────────────────────────────────────

    public void changed(Player player, ArenaDefinition def, String message) {
        plugin.arenas().onDefinitionChanged(def);
        player.sendActionBar(Msg.mm("<green>✔ " + Msg.escape(message)));
        Msg.success(player);
        if (sessions.containsKey(player.getUniqueId())) {
            equipHotbar(player, def);
        }
    }

    public void fail(Player player, String message) {
        player.sendActionBar(Msg.mm("<red>✘ " + Msg.escape(message)));
        Msg.error(player);
    }

    // ── region selection ─────────────────────────────────────────────────────

    public void setCorner(Player player, ArenaDefinition def, int corner, BlockPos pos) {
        String world = player.getWorld().getName();
        if (def.world() != null && !def.world().equals(world)) {
            def.pos1(null);
            def.pos2(null);
        }
        def.world(world);
        if (corner == 1) def.pos1(pos); else def.pos2(pos);
        changed(player, def, "Position " + corner + " set to " + pos.pretty());
    }

    public void giveWand(Player player) {
        ItemStack wand = ItemBuilder.of(Material.GOLDEN_AXE)
                .name("<gold><bold>Arena Selection Wand")
                .lore("<yellow>Left-click <gray>a block → <white>position 1",
                        "<yellow>Right-click <gray>a block → <white>position 2")
                .tag(toolKey, "wand")
                .tag(wandKey, "1")
                .glow(true).build();
        plugin.scheduler().entity(player, () -> player.getInventory().addItem(wand));
    }

    // ── event handling ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (!isTool(item)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String arenaId = sessions.get(uuid);
        ArenaDefinition def = arenaId == null ? null : plugin.arenas().definition(arenaId).orElse(null);
        if (def == null) {
            fail(player, "Your setup session has expired or the arena was deleted.");
            exit(player);
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastActionTimes.getOrDefault(uuid, 0L);
        if (now - last < 150) return;
        lastActionTimes.put(uuid, now);

        String tool = toolType(item);
        Action action = event.getAction();
        boolean shift = player.isSneaking();

        switch (tool) {
            case "wand" -> {
                if (action == Action.LEFT_CLICK_BLOCK && event.getClickedBlock() != null) {
                    setCorner(player, def, 1, BlockPos.of(event.getClickedBlock()));
                } else if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                    setCorner(player, def, 2, BlockPos.of(event.getClickedBlock()));
                } else {
                    fail(player, "Click a block to set Pos 1 (Left-click) or Pos 2 (Right-click).");
                }
            }
            case "spawns" -> {
                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    def.waitingLobby(LocationData.of(player.getLocation()));
                    changed(player, def, "Waiting lobby spawn set to your position");
                } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                    def.spectatorSpawn(LocationData.of(player.getLocation()));
                    changed(player, def, "Spectator spawn set to your position");
                }
            }
            case "team_base" -> {
                if (shift) {
                    cycleTeam(player, def);
                } else if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    TeamColor color = currentTeam(player, def);
                    TeamDefinition t = def.team(color);
                    t.spawn(LocationData.of(player.getLocation()));
                    changed(player, def, color.displayName() + " spawn set to your position");
                } else if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
                    Block target = action == Action.RIGHT_CLICK_BLOCK ? event.getClickedBlock() : player.getTargetBlockExact(8);
                    if (target != null && Tag.BEDS.isTagged(target.getType())) {
                        TeamColor color = currentTeam(player, def);
                        TeamDefinition t = def.team(color);
                        t.bed(BlockPos.of(target));
                        changed(player, def, color.displayName() + " bed set to " + BlockPos.of(target).pretty());
                    } else {
                        fail(player, "Right-click directly on a Bed block to set the team's bed!");
                    }
                }
            }
            case "team_npc" -> {
                if (shift) {
                    cycleTeam(player, def);
                } else if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    TeamColor color = currentTeam(player, def);
                    TeamDefinition t = def.team(color);
                    t.shopNpc(LocationData.centered(player.getLocation()));
                    changed(player, def, color.displayName() + " Shop NPC set at your feet");
                } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                    TeamColor color = currentTeam(player, def);
                    TeamDefinition t = def.team(color);
                    t.upgradeNpc(LocationData.centered(player.getLocation()));
                    changed(player, def, color.displayName() + " Upgrade NPC set at your feet");
                }
            }
            case "gen_base" -> {
                LocationData at = LocationData.centered(player.getLocation());
                TeamColor owner = detectOwner(def, at, currentTeam(player, def));
                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    def.addGenerator(GeneratorType.IRON, at, owner);
                    changed(player, def, "Iron generator added" + (owner != null ? " for Team " + owner.displayName() : ""));
                } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                    def.addGenerator(GeneratorType.GOLD, at, owner);
                    changed(player, def, "Gold generator added" + (owner != null ? " for Team " + owner.displayName() : ""));
                }
            }
            case "gen_tier" -> {
                LocationData at = LocationData.centered(player.getLocation());
                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    def.addGenerator(GeneratorType.DIAMOND, at, null);
                    changed(player, def, "Diamond generator added (public)");
                } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                    def.addGenerator(GeneratorType.EMERALD, at, null);
                    changed(player, def, "Emerald generator added (public)");
                }
            }
            case "remover" -> handleRemoverClick(player, def, event.getClickedBlock());
            case "menu" -> openHub(player, def);
            case "exit" -> exit(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!sessions.containsKey(uuid)) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isTool(item)) return;

        // If pointing at a nearby block, PlayerInteractEvent will handle it
        if (player.getTargetBlockExact(4) != null) return;

        long now = System.currentTimeMillis();
        long last = lastActionTimes.getOrDefault(uuid, 0L);
        if (now - last < 250) return;
        lastActionTimes.put(uuid, now);

        String arenaId = sessions.get(uuid);
        ArenaDefinition def = arenaId == null ? null : plugin.arenas().definition(arenaId).orElse(null);
        if (def == null) return;

        String tool = toolType(item);
        boolean shift = player.isSneaking();

        switch (tool) {
            case "spawns" -> {
                def.waitingLobby(LocationData.of(player.getLocation()));
                changed(player, def, "Waiting lobby spawn set to your position");
            }
            case "team_base" -> {
                if (shift) {
                    cycleTeam(player, def);
                } else {
                    TeamColor color = currentTeam(player, def);
                    TeamDefinition t = def.team(color);
                    t.spawn(LocationData.of(player.getLocation()));
                    changed(player, def, color.displayName() + " spawn set to your position");
                }
            }
            case "team_npc" -> {
                if (shift) {
                    cycleTeam(player, def);
                } else {
                    TeamColor color = currentTeam(player, def);
                    TeamDefinition t = def.team(color);
                    t.shopNpc(LocationData.centered(player.getLocation()));
                    changed(player, def, color.displayName() + " Shop NPC set at your feet");
                }
            }
            case "gen_base" -> {
                LocationData at = LocationData.centered(player.getLocation());
                TeamColor owner = detectOwner(def, at, currentTeam(player, def));
                def.addGenerator(GeneratorType.IRON, at, owner);
                changed(player, def, "Iron generator added" + (owner != null ? " for Team " + owner.displayName() : ""));
            }
            case "gen_tier" -> {
                LocationData at = LocationData.centered(player.getLocation());
                def.addGenerator(GeneratorType.DIAMOND, at, null);
                changed(player, def, "Diamond generator added (public)");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isTool(item)) return;

        event.setCancelled(true);
        String tool = toolType(item);
        if (!"remover".equals(tool)) return;

        String arenaId = sessions.get(player.getUniqueId());
        ArenaDefinition def = arenaId == null ? null : plugin.arenas().definition(arenaId).orElse(null);
        if (def == null) return;

        Entity target = event.getRightClicked();
        Location loc = target.getLocation();
        boolean removed = false;

        for (TeamColor c : def.activeColors()) {
            TeamDefinition t = def.teamIfPresent(c);
            if (t == null) continue;
            if (t.shopNpc() != null && t.shopNpc().distanceSquared(loc.getX(), loc.getY(), loc.getZ()) < 4.0) {
                t.shopNpc(null);
                target.remove();
                changed(player, def, "Removed Shop NPC for Team " + c.displayName());
                removed = true;
                break;
            }
            if (t.upgradeNpc() != null && t.upgradeNpc().distanceSquared(loc.getX(), loc.getY(), loc.getZ()) < 4.0) {
                t.upgradeNpc(null);
                target.remove();
                changed(player, def, "Removed Upgrade NPC for Team " + c.displayName());
                removed = true;
                break;
            }
        }
        if (!removed) {
            fail(player, "Entity is not a registered arena NPC.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && sessions.containsKey(player.getUniqueId())) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (isTool(item)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (sessions.containsKey(player.getUniqueId())) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (isTool(item)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (sessions.containsKey(player.getUniqueId())) {
            ItemStack item = event.getItemInHand();
            if (isTool(item)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && sessions.containsKey(player.getUniqueId())) {
            ItemStack current = event.getCurrentItem();
            ItemStack cursor = event.getCursor();
            if (isTool(current) || isTool(cursor)) {
                if (event.getClickedInventory() != null && !(event.getInventory().getHolder() instanceof Menu)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        sessions.remove(uuid);
        teamIndices.remove(uuid);
        lastActionTimes.remove(uuid);
        InventorySnapshot snapshot = snapshots.remove(uuid);
        if (snapshot != null) {
            player.getInventory().clear();
            player.getInventory().setItemInOffHand(null);
            if (snapshot.contents() != null) player.getInventory().setContents(snapshot.contents());
            if (snapshot.armor() != null) player.getInventory().setArmorContents(snapshot.armor());
            if (snapshot.offhand() != null) player.getInventory().setItemInOffHand(snapshot.offhand());
            if (snapshot.mode() != null) player.setGameMode(snapshot.mode());
        }
    }

    // ── internal helpers ─────────────────────────────────────────────────────

    private void handleRemoverClick(Player player, ArenaDefinition def, Block clickedBlock) {
        Location pLoc = player.getLocation();
        double clickX = clickedBlock != null ? clickedBlock.getX() + 0.5 : pLoc.getX();
        double clickY = clickedBlock != null ? clickedBlock.getY() + 0.5 : pLoc.getY();
        double clickZ = clickedBlock != null ? clickedBlock.getZ() + 0.5 : pLoc.getZ();

        // 1. Closest generator within 3.5 blocks
        GeneratorDefinition closestGen = null;
        double minGenDist = 3.5 * 3.5;
        for (GeneratorDefinition g : def.generators()) {
            LocationData loc = g.location();
            if (loc == null || !loc.world().equals(pLoc.getWorld().getName())) continue;
            double d = loc.distanceSquared(clickX, clickY, clickZ);
            if (d < minGenDist) {
                minGenDist = d;
                closestGen = g;
            }
        }
        if (closestGen != null) {
            def.removeGenerator(closestGen);
            changed(player, def, "Removed " + closestGen.type().name() + " generator #" + closestGen.id());
            return;
        }

        // 2. Closest NPC location within 3.5 blocks
        for (TeamColor c : def.activeColors()) {
            TeamDefinition t = def.teamIfPresent(c);
            if (t == null) continue;
            if (t.shopNpc() != null && t.shopNpc().world().equals(pLoc.getWorld().getName())
                    && t.shopNpc().distanceSquared(clickX, clickY, clickZ) < 3.5 * 3.5) {
                t.shopNpc(null);
                changed(player, def, "Removed Shop NPC for Team " + c.displayName());
                return;
            }
            if (t.upgradeNpc() != null && t.upgradeNpc().world().equals(pLoc.getWorld().getName())
                    && t.upgradeNpc().distanceSquared(clickX, clickY, clickZ) < 3.5 * 3.5) {
                t.upgradeNpc(null);
                changed(player, def, "Removed Upgrade NPC for Team " + c.displayName());
                return;
            }
        }

        // 3. Closest Bed within 3.5 blocks
        for (TeamColor c : def.activeColors()) {
            TeamDefinition t = def.teamIfPresent(c);
            if (t == null || t.bed() == null) continue;
            if (t.bed().distanceSquared(clickX, clickY, clickZ) < 3.5 * 3.5) {
                t.bed(null);
                changed(player, def, "Removed Bed for Team " + c.displayName());
                return;
            }
        }

        fail(player, "No generator, NPC or bed within 3.5 blocks to remove.");
    }

    private void cycleTeam(Player player, ArenaDefinition def) {
        List<TeamColor> active = def.activeColors();
        if (active.isEmpty()) return;
        int idx = Math.floorMod(teamIndices.getOrDefault(player.getUniqueId(), 0) + 1, active.size());
        teamIndices.put(player.getUniqueId(), idx);
        TeamColor next = active.get(idx);
        equipHotbar(player, def);
        player.sendActionBar(Msg.mm("<yellow>Switched active team to <" + next.text().asHexString() + "><bold>" + next.displayName()));
        Msg.click(player);
    }

    private TeamColor currentTeam(Player player, ArenaDefinition def) {
        List<TeamColor> active = def.activeColors();
        if (active.isEmpty()) return TeamColor.RED;
        int idx = Math.floorMod(teamIndices.getOrDefault(player.getUniqueId(), 0), active.size());
        return active.get(idx);
    }

    private TeamColor detectOwner(ArenaDefinition def, LocationData at, TeamColor fallback) {
        TeamColor best = null;
        double bestDist = 25.0 * 25.0;
        for (TeamColor color : def.activeColors()) {
            TeamDefinition t = def.teamIfPresent(color);
            if (t == null || t.spawn() == null) continue;
            double d = t.spawn().distanceSquared(at.x(), at.y(), at.z());
            if (d < bestDist) {
                bestDist = d;
                best = color;
            }
        }
        return best != null ? best : fallback;
    }

    private boolean isTool(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(toolKey, PersistentDataType.STRING) || pdc.has(wandKey, PersistentDataType.STRING);
    }

    private String toolType(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return "";
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String val = pdc.get(toolKey, PersistentDataType.STRING);
        if (val != null) return val;
        if (pdc.has(wandKey, PersistentDataType.STRING)) return "wand";
        return "";
    }

    private static ItemStack[] cloneItemArray(ItemStack[] array) {
        if (array == null) return null;
        ItemStack[] cloned = new ItemStack[array.length];
        for (int i = 0; i < array.length; i++) {
            cloned[i] = array[i] == null ? null : array[i].clone();
        }
        return cloned;
    }
}
