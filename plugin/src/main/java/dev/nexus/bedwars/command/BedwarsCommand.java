package dev.nexus.bedwars.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.nexus.bedwars.BedwarsPlugin;
import dev.nexus.bedwars.admin.AdminMenus;
import dev.nexus.bedwars.arena.Game;
import dev.nexus.bedwars.config.Messages;
import dev.nexus.bedwars.db.LeaderboardEntry;
import dev.nexus.bedwars.db.PlayerProfile;
import dev.nexus.bedwars.db.Stat;
import dev.nexus.bedwars.gui.Msg;
import dev.nexus.bedwars.model.ArenaDefinition;
import dev.nexus.bedwars.model.ValidationReport;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * Layer 2 of the triple-layer configuration: the Brigadier command suite with rich auto-completion
 * (arena ids, modes, stats, every config path). Registered through Paper's lifecycle API.
 *
 * <pre>
 * /bedwars join [arena] | leave | stats [player] | top &lt;stat&gt;
 * /bedwars admin                         → opens the admin GUI
 * /bedwars create &lt;id&gt; &lt;solo|doubles|triples|quads&gt;  |  create &lt;id&gt; custom &lt;teams&gt; &lt;size&gt;
 * /bedwars setup &lt;arena&gt;                → setup wizard
 * /bedwars enable|disable|delete|info &lt;arena&gt;
 * /bedwars arena &lt;arena&gt; displayname|teamcount|teamsize|minplayers &lt;value&gt;
 * /bedwars config get|set &lt;path&gt; [value]
 * /bedwars list | reload
 * </pre>
 */
public final class BedwarsCommand {

    private static final String ADMIN = "nexusbw.admin";
    private static final String PLAY = "nexusbw.play";

    private final BedwarsPlugin plugin;

    private BedwarsCommand(BedwarsPlugin plugin) {
        this.plugin = plugin;
    }

    public static void register(BedwarsPlugin plugin) {
        BedwarsCommand cmd = new BedwarsCommand(plugin);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(cmd.build(), "NexusBedwars main command", List.of("bw")));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Tree
    // ═════════════════════════════════════════════════════════════════════════

    private LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("bedwars")
                .requires(s -> s.getSender().hasPermission(PLAY))
                .executes(ctx -> help(ctx.getSource().getSender()));

        // ── players ──────────────────────────────────────────────────────────
        root.then(Commands.literal("join")
                .executes(ctx -> join(ctx, null))
                .then(arenaArg().executes(ctx -> join(ctx, StringArgumentType.getString(ctx, "arena")))));
        root.then(Commands.literal("leave").executes(this::leave));
        root.then(Commands.literal("stats")
                .executes(ctx -> stats(ctx, null))
                .then(Commands.argument("player", StringArgumentType.word()).executes(ctx -> stats(ctx, StringArgumentType.getString(ctx, "player")))));
        root.then(Commands.literal("top").then(Commands.argument("stat", StringArgumentType.word())
                .suggests((ctx, b) -> {
                    for (Stat s : Stat.values()) if (s.name().toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) b.suggest(s.name().toLowerCase(Locale.ROOT));
                    return b.buildFuture();
                }).executes(this::top)));

        // ── admin ────────────────────────────────────────────────────────────
        root.then(admin("admin").executes(ctx -> {
            if (ctx.getSource().getSender() instanceof Player p) AdminMenus.openHome(plugin, p);
            else plugin.messages().send(ctx.getSource().getSender(), "player-only");
            return Command.SINGLE_SUCCESS;
        }));
        root.then(admin("list").executes(this::list));
        root.then(admin("reload").executes(ctx -> {
            plugin.config().reload();
            plugin.arenas().reloadFromDisk();
            plugin.messages().send(ctx.getSource().getSender(), "config-reloaded");
            return Command.SINGLE_SUCCESS;
        }));

        root.then(admin("create").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.literal("solo").executes(ctx -> create(ctx, 8, 1)))
                .then(Commands.literal("doubles").executes(ctx -> create(ctx, 8, 2)))
                .then(Commands.literal("triples").executes(ctx -> create(ctx, 4, 3)))
                .then(Commands.literal("quads").executes(ctx -> create(ctx, 4, 4)))
                .then(Commands.literal("custom")
                        .then(Commands.argument("teams", IntegerArgumentType.integer(2, 8))
                                .then(Commands.argument("size", IntegerArgumentType.integer(1, 16))
                                        .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "teams"), IntegerArgumentType.getInteger(ctx, "size"))))))));

        root.then(admin("setup").then(arenaArg().executes(ctx -> withArena(ctx, (p, def) -> {
            if (p == null) return fail(ctx, "player-only", null);
            plugin.wizard().open(p, def);
            return Command.SINGLE_SUCCESS;
        }))));
        root.then(admin("enable").then(arenaArg().executes(ctx -> withArena(ctx, (p, def) -> {
            ValidationReport report = plugin.arenas().enable(def);
            if (report.valid()) say(ctx, "<green>Arena <white>" + def.id() + "</white> enabled.");
            else {
                say(ctx, "<red>Cannot enable – " + report.failing().size() + " check(s) failing:");
                report.failing().forEach(c -> say(ctx, " <red>✘ <gray>" + c.name() + " <dark_gray>(" + Msg.escape(c.detail()) + ")"));
            }
            return Command.SINGLE_SUCCESS;
        }))));
        root.then(admin("disable").then(arenaArg().executes(ctx -> withArena(ctx, (p, def) -> {
            plugin.arenas().disable(def);
            say(ctx, "<yellow>Arena <white>" + def.id() + "</white> disabled.");
            return Command.SINGLE_SUCCESS;
        }))));
        root.then(admin("delete").then(arenaArg().executes(ctx -> withArena(ctx, (p, def) -> {
            plugin.arenas().delete(def.id());
            say(ctx, "<red>Arena <white>" + def.id() + "</white> deleted.");
            return Command.SINGLE_SUCCESS;
        }))));
        root.then(admin("info").then(arenaArg().executes(ctx -> withArena(ctx, (p, def) -> {
            ValidationReport r = ValidationReport.of(def);
            say(ctx, "<gold>" + def.id() + " <gray>– " + Msg.escape(def.displayName()) + " (" + def.modeName() + ", " + def.minPlayers() + "–" + def.maxPlayers() + " players)");
            r.checks().forEach(c -> say(ctx, (c.passed() ? " <green>✔ " : " <red>✘ ") + "<gray>" + c.name() + " <dark_gray>" + Msg.escape(c.detail())));
            return Command.SINGLE_SUCCESS;
        }))));

        root.then(admin("arena").then(arenaArg()
                .then(Commands.literal("displayname").then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(ctx -> edit(ctx, def -> def.displayName(StringArgumentType.getString(ctx, "value"))))))
                .then(intEdit("teamcount", 2, 8, (def, v) -> def.teamCount(v)))
                .then(intEdit("teamsize", 1, 16, (def, v) -> def.teamSize(v)))
                .then(intEdit("minplayers", 2, 128, (def, v) -> def.minPlayers(v)))));

        root.then(admin("config")
                .then(Commands.literal("get").then(configPath().executes(ctx -> {
                    String path = StringArgumentType.getString(ctx, "path");
                    say(ctx, "<gray>" + path + " = <aqua>" + Msg.escape(String.valueOf(plugin.config().raw(path))));
                    return Command.SINGLE_SUCCESS;
                })))
                .then(Commands.literal("set").then(configPath().then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(this::configSet)))));
        return root.build();
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
        return Commands.literal(name).requires(s -> s.getSender().hasPermission(ADMIN));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> arenaArg() {
        return Commands.argument("arena", StringArgumentType.word()).suggests((ctx, b) -> {
            for (String id : plugin.arenas().ids()) if (id.startsWith(b.getRemainingLowerCase())) b.suggest(id);
            return b.buildFuture();
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> configPath() {
        return Commands.argument("path", StringArgumentType.word()).suggests((ctx, b) -> {
            for (String key : plugin.config().keys()) if (key.toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) b.suggest(key);
            return b.buildFuture();
        });
    }

    private LiteralArgumentBuilder<CommandSourceStack> intEdit(String literal, int min, int max,
                                                               java.util.function.BiConsumer<ArenaDefinition, Integer> apply) {
        return Commands.literal(literal).then(Commands.argument("value", IntegerArgumentType.integer(min, max))
                .executes(ctx -> edit(ctx, def -> apply.accept(def, IntegerArgumentType.getInteger(ctx, "value")))));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Handlers
    // ═════════════════════════════════════════════════════════════════════════

    private int help(CommandSender s) {
        s.sendMessage(Msg.mm("<gradient:#ff5555:#ffaa00><bold>NexusBedwars</bold></gradient> <gray>on " + plugin.platform().implementation()));
        s.sendMessage(Msg.mm("<yellow>/bw join [arena] <gray>· <yellow>/bw leave <gray>· <yellow>/bw stats [player] <gray>· <yellow>/bw top <stat>"));
        if (s.hasPermission(ADMIN)) {
            s.sendMessage(Msg.mm("<red>Admin: <yellow>/bw admin <gray>· <yellow>create <gray>· <yellow>setup <gray>· <yellow>enable <gray>· <yellow>disable <gray>· <yellow>config <gray>· <yellow>reload"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int join(CommandContext<CommandSourceStack> ctx, String arenaId) {
        if (!(ctx.getSource().getSender() instanceof Player p)) return fail(ctx, "player-only", null);
        if (plugin.arenas().gameOf(p.getUniqueId()) != null) return fail(ctx, "already-ingame", null);
        Game g = arenaId == null ? plugin.arenas().bestJoinable() : plugin.arenas().game(arenaId);
        if (g == null) return fail(ctx, arenaId == null ? "no-arena" : "arena-not-ready", arenaId);
        if (!g.join(p)) return fail(ctx, "arena-full", null);
        return Command.SINGLE_SUCCESS;
    }

    private int leave(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player p)) return fail(ctx, "player-only", null);
        Game g = plugin.arenas().gameOf(p.getUniqueId());
        if (g == null) return fail(ctx, "not-ingame", null);
        g.leave(p);
        return Command.SINGLE_SUCCESS;
    }

    private int stats(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSender sender = ctx.getSource().getSender();
        if (name == null) {
            if (!(sender instanceof Player p)) return fail(ctx, "player-only", null);
            sendStats(sender, plugin.stats().profile(p.getUniqueId()));
        } else {
            Player online = org.bukkit.Bukkit.getPlayerExact(name);
            if (online != null && plugin.stats().cached(online.getUniqueId()).isPresent()) {
                sendStats(sender, plugin.stats().profile(online.getUniqueId()));
                return Command.SINGLE_SUCCESS;
            }
            plugin.database().findByName(name).thenAccept(opt -> {
                if (opt.isEmpty()) sender.sendMessage(Msg.mm("<red>No stats found for <white>" + Msg.escape(name)));
                else sendStats(sender, opt.get());
            });
        }
        return Command.SINGLE_SUCCESS;
    }

    private void sendStats(CommandSender to, PlayerProfile p) {
        String line = "<gray>";
        to.sendMessage(Msg.mm("<gold><bold>Bedwars stats <gray>– <white>" + Msg.escape(p.name())));
        to.sendMessage(Msg.mm(line + "Wins <white>" + p.get(Stat.WINS) + line + " | Losses <white>" + p.get(Stat.LOSSES)
                + line + " | WLR <white>" + String.format(Locale.ROOT, "%.2f", p.ratio(Stat.WINS, Stat.LOSSES))));
        to.sendMessage(Msg.mm(line + "Kills <white>" + p.get(Stat.KILLS) + line + " | Final kills <white>" + p.get(Stat.FINAL_KILLS)
                + line + " | FKDR <white>" + String.format(Locale.ROOT, "%.2f", p.ratio(Stat.FINAL_KILLS, Stat.FINAL_DEATHS))));
        to.sendMessage(Msg.mm(line + "Beds broken <white>" + p.get(Stat.BEDS_BROKEN) + line + " | Games <white>" + p.get(Stat.GAMES_PLAYED)));
        to.sendMessage(Msg.mm(line + "Winstreak <white>" + p.get(Stat.WINSTREAK) + line + " (best <white>" + p.get(Stat.BEST_WINSTREAK)
                + line + ") | Elo <aqua>" + p.get(Stat.ELO)));
    }

    private int top(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var stat = Stat.parse(StringArgumentType.getString(ctx, "stat"));
        if (stat.isEmpty()) {
            sender.sendMessage(Msg.mm("<red>Unknown stat."));
            return 0;
        }
        List<LeaderboardEntry> board = plugin.stats().leaderboard(stat.get());
        sender.sendMessage(Msg.mm("<gold><bold>Top 10 – " + stat.get().name().toLowerCase(Locale.ROOT).replace('_', ' ')));
        if (board.isEmpty()) sender.sendMessage(Msg.mm("<gray>No data yet."));
        for (LeaderboardEntry e : board) {
            sender.sendMessage(Msg.mm("<yellow>#" + e.rank() + " <white>" + Msg.escape(e.name()) + " <gray>– <aqua>" + e.value()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        say(ctx, "<gold>Arenas (" + plugin.arenas().definitions().size() + "):");
        for (ArenaDefinition a : plugin.arenas().definitions()) {
            Game g = plugin.arenas().game(a.id());
            say(ctx, " <white>" + a.id() + " <gray>" + a.modeName() + " – " + (a.enabled()
                    ? "<green>enabled <gray>(" + (g == null ? "?" : g.state() + ", " + g.playerCount() + "/" + a.maxPlayers()) + ")" : "<red>disabled"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int create(CommandContext<CommandSourceStack> ctx, int teams, int size) {
        String id = StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_\\-]{1,32}")) {
            say(ctx, "<red>Arena ids may only contain a-z, 0-9, '_' and '-'.");
            return 0;
        }
        if (plugin.arenas().definition(id).isPresent()) {
            say(ctx, "<red>That arena already exists.");
            return 0;
        }
        ArenaDefinition def = plugin.arenas().create(id, teams, size);
        say(ctx, "<green>Created arena <white>" + id + " <gray>(" + def.modeName() + "). Run <yellow>/bw setup " + id + "</yellow> to configure it.");
        if (ctx.getSource().getSender() instanceof Player p) plugin.wizard().open(p, def);
        return Command.SINGLE_SUCCESS;
    }

    private int withArena(CommandContext<CommandSourceStack> ctx, BiFunction<Player, ArenaDefinition, Integer> action) {
        String id = StringArgumentType.getString(ctx, "arena");
        ArenaDefinition def = plugin.arenas().definition(id).orElse(null);
        if (def == null) return fail(ctx, "arena-not-found", id);
        Player p = ctx.getSource().getSender() instanceof Player pl ? pl : null;
        return action.apply(p, def);
    }

    private int edit(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<ArenaDefinition> change) {
        return withArena(ctx, (p, def) -> {
            change.accept(def);
            plugin.arenas().onDefinitionChanged(def);
            say(ctx, "<green>Updated <white>" + def.id() + " <gray>(" + def.modeName() + ", min " + def.minPlayers() + ")");
            return Command.SINGLE_SUCCESS;
        });
    }

    private int configSet(CommandContext<CommandSourceStack> ctx) {
        String path = StringArgumentType.getString(ctx, "path");
        String input = StringArgumentType.getString(ctx, "value");
        Object existing = plugin.config().raw(path);
        if (existing == null) {
            say(ctx, "<red>Unknown config path.");
            return 0;
        }
        Object value;
        try {
            if (existing instanceof Boolean) value = Boolean.parseBoolean(input);
            else if (existing instanceof Integer) value = Integer.parseInt(input);
            else if (existing instanceof Number) value = Double.parseDouble(input);
            else if (existing instanceof List<?>) {
                say(ctx, "<red>Lists must be edited in config.yml (hot-reloaded on save).");
                return 0;
            } else value = input;
        } catch (NumberFormatException e) {
            say(ctx, "<red>Expected a number.");
            return 0;
        }
        plugin.config().set(path, value);
        say(ctx, "<green>" + path + " <gray>= <aqua>" + Msg.escape(String.valueOf(value)) + " <dark_gray>(applied live)");
        return Command.SINGLE_SUCCESS;
    }

    // ── feedback ─────────────────────────────────────────────────────────────

    private void say(CommandContext<CommandSourceStack> ctx, String miniMessage) {
        ctx.getSource().getSender().sendMessage(plugin.messages().parse(plugin.config().getString("messages.prefix", "") + " " + miniMessage));
    }

    private int fail(CommandContext<CommandSourceStack> ctx, String key, String arena) {
        plugin.messages().send(ctx.getSource().getSender(), key, Messages.text("arena", arena == null ? "" : arena));
        return 0;
    }
}
