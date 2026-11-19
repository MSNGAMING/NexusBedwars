package dev.nexus.bedwars.setup;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.gui.ItemBuilder;
import dev.nexus.bedwars.gui.Msg;
import dev.nexus.bedwars.model.ArenaDefinition;
import dev.nexus.bedwars.model.BlockPos;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Step-by-step arena setup wizard.
 *
 * <p>The wizard is a thin controller: it keeps one session per admin (which arena is being edited),
 * routes between the step menus in {@link SetupMenus} and handles the selection wand.
 * All edits go straight into the live {@link ArenaDefinition}; {@link #changed} then persists to
 * arenas.yml (debounced, async) and re-validates a running arena – so GUI, commands and the YAML
 * file always agree.
 */
public final class SetupWizard implements Listener {

    private final BedwarsPlugin plugin;
    private final NamespacedKey wandKey;
    private final Map<UUID, String> sessions = new ConcurrentHashMap<>();

    public SetupWizard(BedwarsPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "setup_wand");
    }

    public BedwarsPlugin plugin() { return plugin; }

    // ── navigation ───────────────────────────────────────────────────────────

    /** Entry point used by /bedwars setup &lt;arena&gt;. */
    public void open(Player player, ArenaDefinition def) {
        sessions.put(player.getUniqueId(), def.id());
        openHub(player, def);
    }

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

    /** Call after every mutation of an {@link ArenaDefinition} from the GUI. */
    public void changed(Player player, ArenaDefinition def, String message) {
        plugin.arenas().onDefinitionChanged(def);
        player.sendActionBar(Msg.mm("<green>✔ " + Msg.escape(message)));
        Msg.success(player);
    }

    public void fail(Player player, String message) {
        player.sendActionBar(Msg.mm("<red>✘ " + Msg.escape(message)));
        Msg.error(player);
    }

    // ── region selection (shared by GUI and wand) ────────────────────────────

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

    // ── selection wand ───────────────────────────────────────────────────────

    public void giveWand(Player player) {
        ItemStack wand = ItemBuilder.of(Material.GOLDEN_AXE)
                .name("<gold><bold>Arena Selection Wand")
                .lore("<yellow>Left-click <gray>a block → <white>position 1",
                        "<yellow>Right-click <gray>a block → <white>position 2")
                .tag(wandKey, "1").glow(true).build();
        plugin.scheduler().entity(player, () -> player.getInventory().addItem(wand));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWandUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()
                || !item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.STRING)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        String arenaId = sessions.get(player.getUniqueId());
        ArenaDefinition def = arenaId == null ? null : plugin.arenas().definition(arenaId).orElse(null);
        if (def == null) {
            fail(player, "Open /bedwars setup <arena> first.");
            return;
        }
        Block block = event.getClickedBlock();
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK) setCorner(player, def, 1, BlockPos.of(block));
        else if (action == Action.RIGHT_CLICK_BLOCK) setCorner(player, def, 2, BlockPos.of(block));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }
}
