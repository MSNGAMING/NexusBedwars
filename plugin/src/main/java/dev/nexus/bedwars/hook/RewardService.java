package dev.nexus.bedwars.hook;

import dev.nexus.bedwars.BedwarsPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

/**
 * Optional Vault payouts (kill / final-kill / bed / win). The Vault classes are only touched through the
 * nested {@link VaultBridge}, which is loaded lazily – a server without Vault never sees a NoClassDefFoundError.
 */
public final class RewardService {

    private final BedwarsPlugin plugin;
    private volatile VaultBridge bridge;

    public RewardService(BedwarsPlugin plugin) {
        this.plugin = plugin;
    }

    private VaultBridge bridge() {
        if (bridge != null) return bridge;
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            bridge = VaultBridge.create();
            if (bridge != null) plugin.getLogger().info("Vault economy hooked – rewards enabled.");
        }
        return bridge;
    }

    /** @param key one of kill, final-kill, bed, win (config.yml → rewards.*) */
    public void reward(UUID player, String key) {
        double amount = plugin.config().getDouble("rewards." + key, 0.0);
        VaultBridge vb = bridge();
        if (vb == null || amount <= 0) return;
        org.bukkit.entity.Player online = Bukkit.getPlayer(player);
        if (online != null) {
            plugin.scheduler().entity(online, () -> vb.deposit(player, amount));
        } else {
            plugin.scheduler().global(() -> vb.deposit(player, amount));
        }
    }

    private static final class VaultBridge {
        private final Economy economy;

        private VaultBridge(Economy economy) { this.economy = economy; }

        static VaultBridge create() {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            return rsp == null ? null : new VaultBridge(rsp.getProvider());
        }

        void deposit(UUID id, double amount) {
            economy.depositPlayer(Bukkit.getOfflinePlayer(id), amount);
        }
    }
}
