package dev.nexus.bedwars.gui;

import dev.nexus.bedwars.BedwarsPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * "Type the new value in chat" prompts for GUI text edits (e.g. arena display name).
 * The callback is re-dispatched onto the player's entity thread because
 * {@link AsyncChatEvent} fires asynchronously.
 */
public final class ChatPrompt implements Listener {

    private final BedwarsPlugin plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public ChatPrompt(BedwarsPlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player player, String question, Consumer<String> callback) {
        pending.put(player.getUniqueId(), callback);
        plugin.scheduler().entity(player, () -> {
            player.closeInventory();
            player.sendMessage(Msg.mm("<gold>» <yellow>" + question + " <gray>(type <white>cancel</white> to abort)"));
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Consumer<String> callback = pending.remove(event.getPlayer().getUniqueId());
        if (callback == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();
        if (text.equalsIgnoreCase("cancel")) {
            player.sendMessage(Msg.mm("<red>Cancelled."));
            return;
        }
        plugin.scheduler().entity(player, () -> callback.accept(text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
