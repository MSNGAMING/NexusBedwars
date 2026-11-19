package dev.nexus.bedwars.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** MiniMessage-backed message catalogue (config.yml → messages.*). Hot-reload aware. */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final ConfigService config;

    public Messages(ConfigService config) {
        this.config = config;
    }

    public Component get(String key, TagResolver... resolvers) {
        String raw = config.getString("messages." + key, "<red>Missing message: " + key);
        TagResolver prefix = Placeholder.parsed("prefix", config.getString("messages.prefix", ""));
        return MM.deserialize(raw, TagResolver.resolver(TagResolver.resolver(resolvers), prefix));
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(key, resolvers));
    }

    public Component parse(String raw, TagResolver... resolvers) {
        return MM.deserialize(raw, resolvers);
    }

    public static TagResolver text(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver comp(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
