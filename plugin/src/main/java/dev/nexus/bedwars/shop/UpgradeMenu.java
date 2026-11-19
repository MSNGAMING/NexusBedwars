package dev.nexus.bedwars.shop;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.arena.Game;
import dev.nexus.bedwars.arena.TeamState;
import dev.nexus.bedwars.arena.TeamState.Trap;
import dev.nexus.bedwars.arena.TeamState.Upgrade;
import dev.nexus.bedwars.config.Messages;
import dev.nexus.bedwars.gui.ItemBuilder;
import dev.nexus.bedwars.gui.Menu;
import dev.nexus.bedwars.gui.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Team upgrade & trap shop. Prices (in diamonds) and level counts are fully config driven. */
public final class UpgradeMenu extends Menu {

    private final Game game;
    private final TeamState team;

    public UpgradeMenu(BedwarsPlugin plugin, Game game, TeamState team) {
        super(plugin, Msg.mm("<dark_gray>Team Upgrades"), 4);
        this.game = game;
        this.team = team;
    }

    @Override
    protected void render() {
        int slot = 10;
        for (Upgrade u : Upgrade.values()) {
            final Upgrade upgrade = u;
            List<Integer> costs = costs("upgrades." + u.key() + ".costs");
            int level = team.level(u);
            boolean maxed = level >= costs.size();
            Material icon = Material.matchMaterial(plugin.config().getString("upgrades." + u.key() + ".icon", "STONE"));
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Level: <white>" + level + "<gray>/<white>" + costs.size());
            lore.add(maxed ? "<green>Maxed out" : "<gray>Next cost: <aqua>" + costs.get(level) + " diamonds");
            set(slot++, ItemBuilder.of(icon == null ? Material.STONE : icon)
                    .name("<yellow>" + plugin.config().getString("upgrades." + u.key() + ".name", u.key()))
                    .lore(lore).glow(level > 0).build(), c -> buyUpgrade(c.player(), upgrade, costs));
        }

        // traps
        int max = plugin.config().getInt("upgrades.traps.max-queue", 3);
        List<Integer> trapCosts = costs("upgrades.traps.costs");
        int queued = team.traps().size();
        int price = trapCosts.isEmpty() ? 1 : trapCosts.get(Math.min(queued, trapCosts.size() - 1));
        int tslot = 19;
        for (Trap trap : Trap.values()) {
            set(tslot++, ItemBuilder.of(trap.icon()).name("<red>" + trap.display())
                    .lore("<gray>" + trap.description(), "", queued >= max ? "<red>Trap queue full" : "<gray>Cost: <aqua>" + price + " diamonds").build(),
                    c -> buyTrap(c.player(), trap, price, max));
        }
        List<String> queueLore = new ArrayList<>();
        int i = 1;
        for (Trap t : team.traps()) queueLore.add("<gray>" + i++ + ". <white>" + t.display());
        if (queueLore.isEmpty()) queueLore.add("<dark_gray>No traps queued");
        set(25, ItemBuilder.of(Material.PAPER).name("<gold>Trap queue <gray>(" + queued + "/" + max + ")").lore(queueLore).build());
        fillEmpty(Msg.filler());
    }

    private List<Integer> costs(String path) {
        List<Integer> out = new ArrayList<>();
        for (Object o : plugin.config().list(path)) if (o instanceof Number n) out.add(n.intValue());
        return out;
    }

    private boolean pay(Player p, int diamonds) {
        if (ShopService.charge(p.getInventory(), Material.DIAMOND, diamonds)) return true;
        plugin.messages().send(p, "cant-afford", Messages.text("amount", diamonds), Messages.text("item", "Diamond"));
        Msg.error(p);
        return false;
    }

    private void buyUpgrade(Player p, Upgrade u, List<Integer> costs) {
        int level = team.level(u);
        if (level >= costs.size() || !pay(p, costs.get(level))) return;
        team.level(u, level + 1);
        game.refreshUpgrades(team);
        announce(p, plugin.config().getString("upgrades." + u.key() + ".name", u.key()), level + 1);
        rebuild();
    }

    private void buyTrap(Player p, Trap trap, int price, int max) {
        if (team.traps().size() >= max || !pay(p, price)) return;
        team.traps().add(trap);
        announce(p, trap.display(), team.traps().size());
        rebuild();
    }

    private void announce(Player buyer, String what, int level) {
        Component msg = plugin.messages().get("upgrade-bought", Messages.text("player", buyer.getName()),
                Messages.text("upgrade", what), Messages.text("level", level));
        for (UUID id : team.members()) {
            Player m = Bukkit.getPlayer(id);
            if (m != null) m.sendMessage(msg);
        }
        Msg.success(buyer);
    }
}
