package dev.nexus.bedwars;

import dev.nexus.bedwars.platform.PlatformInfo;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Modern Paper bootstrap. Runs before the server is created, so it may only touch
 * the {@link BootstrapContext}. We use it for platform detection and to hand the
 * result to the plugin constructor (no static singletons anywhere in the code base).
 */
public final class BedwarsBootstrap implements PluginBootstrap {

    private PlatformInfo platform;

    @Override
    public void bootstrap(BootstrapContext context) {
        this.platform = PlatformInfo.detect();
        context.getLogger().info("NexusBedwars bootstrap on {} (regionised threading: {})",
                platform.implementation(), platform.folia());
    }

    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) {
        return new BedwarsPlugin(platform != null ? platform : PlatformInfo.detect());
    }
}
