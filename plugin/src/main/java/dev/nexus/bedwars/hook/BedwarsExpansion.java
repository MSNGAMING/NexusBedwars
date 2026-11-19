package dev.nexus.bedwars.hook;

import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.arena.Game;
import dev.nexus.bedwars.arena.TeamState;
import dev.nexus.bedwars.db.LeaderboardEntry;
import dev.nexus.bedwars.db.PlayerProfile;
import dev.nexus.bedwars.db.Stat;
import dev.nexus.bedwars.model.ArenaDefinition;
import dev.nexus.bedwars.model.TeamColor;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * PlaceholderAPI expansion – identifier {@code bedwars}. Works with any scoreboard / tab / hologram
 * provider (DecentHolograms, FancyHolograms, TAB, …). Reads only from in-memory caches, so it is safe
 * to be called from any thread at any frequency.
 *
 * <pre>
 * Player   %bedwars_wins% %bedwars_losses% %bedwars_kills% %bedwars_deaths% %bedwars_final_kills% %bedwars_final_deaths%
 *          %bedwars_beds_broken% %bedwars_games_played% %bedwars_winstreak% %bedwars_best_winstreak% %bedwars_elo%
 *          %bedwars_kdr% %bedwars_fkdr% %bedwars_wlr%
 * Current  %bedwars_arena% %bedwars_state% %bedwars_mode% %bedwars_players% %bedwars_max_players% %bedwars_time% %bedwars_countdown%
 *          %bedwars_team% %bedwars_team_color% %bedwars_team_alive% %bedwars_team_bed% %bedwars_team_forge%
 * Teams    %bedwars_team_RED_status%  (✔ bed alive / number of players left / ✘ eliminated)   %bedwars_team_RED_players%
 * Arenas   %bedwars_arena_&lt;id&gt;_state|players|max|mode|display%
 * Server   %bedwars_server_playing% %bedwars_server_arenas% %bedwars_server_open%
 * Top      %bedwars_top_&lt;stat&gt;_&lt;1-10&gt;_name|value%   e.g. %bedwars_top_wins_1_name%
 * </pre>
 */
public final class BedwarsExpansion extends PlaceholderExpansion {

    private final BedwarsPlugin plugin;

    public BedwarsExpansion(BedwarsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "bedwars"; }
    @Override public String getAuthor() { return "NexusDev"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);

        // ── server-wide ──────────────────────────────────────────────────────
        switch (p) {
            case "server_playing" -> { return String.valueOf(plugin.arenas().games().stream().mapToInt(Game::playerCount).sum()); }
            case "server_arenas" -> { return String.valueOf(plugin.arenas().games().size()); }
            case "server_open" -> { return String.valueOf(plugin.arenas().games().stream().filter(g -> g.state().joinable()).count()); }
            default -> { }
        }

        // ── leaderboards: top_<stat>_<rank>_<name|value> ────────────────────
        if (p.startsWith("top_")) return top(p.substring(4));

        // ── arena_<id>_<field> ──────────────────────────────────────────────
        if (p.startsWith("arena_") && p.length() > 6) {
            int idx = p.lastIndexOf('_');
            if (idx > 6) return arenaField(p.substring(6, idx), p.substring(idx + 1));
        }

        if (player == null) return "";

        // ── player statistics ───────────────────────────────────────────────
        Optional<PlayerProfile> profile = plugin.stats().cached(player.getUniqueId());
        Optional<Stat> stat = Stat.parse(p);
        if (stat.isPresent()) return profile.map(pr -> String.valueOf(pr.get(stat.get()))).orElse("0");
        switch (p) {
            case "kdr" -> { return profile.map(pr -> fmt(pr.ratio(Stat.KILLS, Stat.DEATHS))).orElse("0.00"); }
            case "fkdr" -> { return profile.map(pr -> fmt(pr.ratio(Stat.FINAL_KILLS, Stat.FINAL_DEATHS))).orElse("0.00"); }
            case "wlr" -> { return profile.map(pr -> fmt(pr.ratio(Stat.WINS, Stat.LOSSES))).orElse("0.00"); }
            default -> { }
        }

        // ── current game ────────────────────────────────────────────────────
        Game game = plugin.arenas().gameOf(player.getUniqueId());
        if (p.startsWith("team_") && p.split("_").length == 3) { // team_RED_status
            String[] parts = p.split("_");
            TeamColor color = TeamColor.parse(parts[1]);
            if (color == null || game == null) return "";
            TeamState ts = game.team(color);
            if (ts == null) return "";
            return switch (parts[2]) {
                case "status" -> ts.bedAlive() ? "✔" : ts.eliminated() ? "✘" : String.valueOf(ts.members().size());
                case "players" -> String.valueOf(ts.members().size());
                case "bed" -> ts.bedAlive() ? "✔" : "✘";
                default -> "";
            };
        }
        if (game == null) {
            return switch (p) {
                case "arena", "state", "mode", "team", "team_color" -> "";
                default -> null;
            };
        }
        TeamColor color = game.teamOf(player.getUniqueId());
        TeamState team = color == null ? null : game.team(color);
        return switch (p) {
            case "arena" -> game.definition().displayName();
            case "state" -> game.state().name();
            case "mode" -> game.definition().modeName();
            case "players" -> String.valueOf(game.playerCount());
            case "max_players" -> String.valueOf(game.definition().maxPlayers());
            case "time" -> String.format("%02d:%02d", game.elapsedSeconds() / 60, game.elapsedSeconds() % 60);
            case "countdown" -> String.valueOf(Math.max(0, game.countdownSeconds()));
            case "team" -> color == null ? "" : color.displayName();
            case "team_color" -> color == null ? "" : "<" + color.text().asHexString() + ">";
            case "team_alive" -> team == null ? "0" : String.valueOf(team.members().size());
            case "team_bed" -> team == null ? "" : team.bedAlive() ? "✔" : "✘";
            case "team_forge" -> team == null ? "0" : String.valueOf(team.level(TeamState.Upgrade.FORGE));
            default -> null;
        };
    }

    private String arenaField(String id, String field) {
        Optional<ArenaDefinition> def = plugin.arenas().definition(id);
        if (def.isEmpty()) return "";
        Game game = plugin.arenas().game(id);
        return switch (field) {
            case "state" -> game == null ? "DISABLED" : game.state().name();
            case "players" -> game == null ? "0" : String.valueOf(game.playerCount());
            case "max" -> String.valueOf(def.get().maxPlayers());
            case "mode" -> def.get().modeName();
            case "display" -> def.get().displayName();
            default -> "";
        };
    }

    private String top(String rest) { // <stat>_<rank>_<name|value>
        String[] parts = rest.split("_");
        if (parts.length < 3) return "";
        String field = parts[parts.length - 1];
        int rank;
        try {
            rank = Integer.parseInt(parts[parts.length - 2]);
        } catch (NumberFormatException e) {
            return "";
        }
        String statName = String.join("_", java.util.Arrays.copyOfRange(parts, 0, parts.length - 2));
        Optional<Stat> stat = Stat.parse(statName);
        if (stat.isEmpty()) return "";
        List<LeaderboardEntry> board = plugin.stats().leaderboard(stat.get());
        if (rank < 1 || rank > board.size()) return field.equals("name") ? "-" : "0";
        LeaderboardEntry entry = board.get(rank - 1);
        return field.equals("name") ? entry.name() : String.valueOf(entry.value());
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
