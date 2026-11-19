package dev.nexus.bedwars.arena;

import dev.nexus.bedwars.model.*;
import dev.nexus.bedwars.model.ArenaDefinition.GeneratorDefinition;
import dev.nexus.bedwars.model.ArenaDefinition.TeamDefinition;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** (De)serialisation of arenas.yml, including the structured comments. Pure, thread-safe. */
public final class ArenaRepository {

    private ArenaRepository() {}

    public static String toYaml(Collection<ArenaDefinition> arenas) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "NexusBedwars – arenas.yml",
                "Written by the setup wizard (/bedwars setup <arena>) but safe to edit by hand.",
                "The file is hot-reloaded. Arenas that currently host players keep their old",
                "definition until the round is over."));
        ConfigurationSection root = yaml.createSection("arenas");
        yaml.setComments("arenas", List.of("All arenas, keyed by id."));

        for (ArenaDefinition a : arenas) {
            ConfigurationSection s = root.createSection(a.id());
            String p = "arenas." + a.id();
            s.set("display-name", a.displayName());
            s.set("enabled", a.enabled());
            yaml.setComments(p + ".enabled", List.of("Only true when every setup check passes."));
            if (a.world() != null) s.set("world", a.world());

            ConfigurationSection region = s.createSection("region");
            if (a.pos1() != null) a.pos1().write(region, "pos1");
            if (a.pos2() != null) a.pos2().write(region, "pos2");
            yaml.setComments(p + ".region", List.of("Build limit: blocks outside this cuboid cannot be modified."));

            ConfigurationSection mode = s.createSection("mode");
            mode.set("team-count", a.teamCount());
            mode.set("team-size", a.teamSize());
            mode.set("min-players", a.minPlayers());
            yaml.setComments(p + ".mode", List.of("Solo 8×1 | Doubles 8×2 | 3v3v3v3 4×3 | 4v4v4v4 4×4 | or any custom combination."));

            if (a.waitingLobby() != null) a.waitingLobby().write(s, "lobby");
            if (a.spectatorSpawn() != null) a.spectatorSpawn().write(s, "spectator");

            ConfigurationSection teams = s.createSection("teams");
            yaml.setComments(p + ".teams", List.of("Per team: spawn, bed block, shop NPC and upgrade NPC."));
            for (TeamColor color : TeamColor.values()) {
                TeamDefinition t = a.teamIfPresent(color);
                if (t == null) continue;
                ConfigurationSection ts = teams.createSection(color.name());
                if (t.spawn() != null) t.spawn().write(ts, "spawn");
                if (t.bed() != null) t.bed().write(ts, "bed");
                if (t.shopNpc() != null) t.shopNpc().write(ts, "shop-npc");
                if (t.upgradeNpc() != null) t.upgradeNpc().write(ts, "upgrade-npc");
            }

            ConfigurationSection gens = s.createSection("generators");
            yaml.setComments(p + ".generators", List.of(
                    "type: IRON|GOLD|DIAMOND|EMERALD  team: owning team (base generators only)",
                    "split-drop: hand the drop directly to every player standing nearby",
                    "speed-multiplier: scales the tier interval from config.yml"));
            for (GeneratorDefinition g : a.generators()) {
                ConfigurationSection gs = gens.createSection(g.id());
                gs.set("type", g.type().name());
                if (g.owner() != null) gs.set("team", g.owner().name());
                gs.set("split-drop", g.splitDrop());
                gs.set("speed-multiplier", g.speedMultiplier());
                g.location().write(gs, "location");
            }
        }
        return yaml.saveToString();
    }

    public static List<ArenaDefinition> fromYaml(YamlConfiguration yaml) {
        List<ArenaDefinition> out = new ArrayList<>();
        ConfigurationSection root = yaml.getConfigurationSection("arenas");
        if (root == null) return out;

        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            ArenaDefinition a = new ArenaDefinition(id);
            a.displayName(s.getString("display-name", id));
            a.world(s.getString("world"));
            ConfigurationSection region = s.getConfigurationSection("region");
            if (region != null) {
                a.pos1(BlockPos.read(region, "pos1"));
                a.pos2(BlockPos.read(region, "pos2"));
            }
            ConfigurationSection mode = s.getConfigurationSection("mode");
            if (mode != null) {
                a.teamCount(mode.getInt("team-count", 4));
                a.teamSize(mode.getInt("team-size", 2));
                a.minPlayers(mode.getInt("min-players", 2));
            }
            a.waitingLobby(LocationData.read(s, "lobby"));
            a.spectatorSpawn(LocationData.read(s, "spectator"));

            ConfigurationSection teams = s.getConfigurationSection("teams");
            if (teams != null) {
                for (String key : teams.getKeys(false)) {
                    TeamColor color = TeamColor.parse(key);
                    ConfigurationSection ts = teams.getConfigurationSection(key);
                    if (color == null || ts == null) continue;
                    TeamDefinition t = a.team(color);
                    t.spawn(LocationData.read(ts, "spawn"));
                    t.bed(BlockPos.read(ts, "bed"));
                    t.shopNpc(LocationData.read(ts, "shop-npc"));
                    t.upgradeNpc(LocationData.read(ts, "upgrade-npc"));
                }
            }

            ConfigurationSection gens = s.getConfigurationSection("generators");
            if (gens != null) {
                for (String gid : gens.getKeys(false)) {
                    ConfigurationSection gs = gens.getConfigurationSection(gid);
                    if (gs == null) continue;
                    LocationData loc = LocationData.read(gs, "location");
                    GeneratorType type;
                    try {
                        type = GeneratorType.valueOf(gs.getString("type", "IRON").toUpperCase());
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    if (loc == null) continue;
                    GeneratorDefinition g = new GeneratorDefinition(gid, type, loc,
                            gs.getString("team") == null ? null : TeamColor.parse(gs.getString("team")));
                    g.splitDrop(gs.getBoolean("split-drop", false));
                    g.speedMultiplier(gs.getDouble("speed-multiplier", 1.0));
                    a.generators().add(g);
                }
            }
            a.enabled(s.getBoolean("enabled", false));
            out.add(a);
        }
        return out;
    }
}
