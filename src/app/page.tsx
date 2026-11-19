import type { ReactNode } from "react";
import CodeExplorer from "@/components/CodeExplorer";
import { loadPluginFiles, totalLines } from "@/lib/plugin-files";

export const dynamic = "force-static";

const threads = [
  ["Global region", "scheduler.global*", "1 Hz arena loop: countdowns, event timeline, state-machine transitions."],
  ["Region (chunk / location)", "scheduler.region*", "Generators (one timer each), trap watchers, rollback slices, NPC spawn, bed removal."],
  ["Entity", "scheduler.entity*", "Every inventory, gamemode, health, potion, GUI open and respawn timer on a player."],
  ["Async", "scheduler.async*", "JDBC, Redis, YAML writes, file watcher. Never touches Bukkit state."],
];

const states = ["DISABLED", "WAITING", "STARTING", "RUNNING", "ENDING", "RESETTING"];

const layers = [
  {
    title: "1 · Live in-game GUI",
    body: "/bedwars admin → arena list, setup wizard and a paginated live config editor. Booleans toggle, numbers step with left/right/shift-click, text via chat prompt.",
  },
  {
    title: "2 · Brigadier commands",
    body: "Typed arguments with suggestions for arena ids, modes, stats and every config path: /bw config set game.tnt.damage 5.0, /bw arena bw1 teamsize 3 …",
  },
  {
    title: "3 · Hot-reloaded YAML",
    body: "config.yml and arenas.yml are watched (WatchService, debounced). Plugin-originated writes are suppressed, so the three layers never fight.",
  },
];

const wizard = [
  ["Region", "Wand or GUI: pos1 / pos2 from standing block or target block."],
  ["Lobby", "Waiting-lobby spawn with teleport-to & clear."],
  ["Spectator", "Spectator spawn for eliminated players."],
  ["Teams", "Spawn, bed (must target a bed block), shop NPC, upgrade NPC per team."],
  ["Generators", "Iron / gold / diamond / emerald, auto team-owner detection, split-drop, speed multiplier."],
  ["Settings", "Solo · Doubles · 3v3v3v3 · 4v4v4v4 · custom N×M, min players, display name."],
  ["Review", "Live validation checklist – Enable is only possible when every check is green."],
];

const placeholders = [
  ["%bedwars_wins% … %bedwars_elo%", "all 11 persisted stats"],
  ["%bedwars_kdr% %bedwars_fkdr% %bedwars_wlr%", "derived ratios"],
  ["%bedwars_team_RED_status%", "✔ bed / players left / ✘ eliminated"],
  ["%bedwars_arena_<id>_players%", "per-arena state, players, max, mode"],
  ["%bedwars_top_wins_1_name%", "top-10 for 7 stats (name / value)"],
  ["%bedwars_server_playing%", "server-wide counters"],
];

const tree = `NexusBedwars/
├─ build.gradle.kts            Gradle (Kotlin DSL), Java 21 target
├─ src/main/resources/
│  ├─ paper-plugin.yml         bootstrapper + loader + folia-supported
│  ├─ config.yml               hot-reloaded, commented
│  └─ arenas.yml               written by the wizard, hot-reloaded
└─ src/main/java/dev/nexus/bedwars/
   ├─ BedwarsBootstrap / BedwarsLoader / BedwarsPlugin
   ├─ platform/   Scheduler · Compat · PlatformInfo
   ├─ config/     ConfigService · FileWatcher · Messages
   ├─ model/      ArenaDefinition · ValidationReport · TeamColor · …
   ├─ arena/      ArenaState(+Machine) · Game · ArenaManager · ArenaRollback · TeamState
   ├─ setup/      SetupWizard · SetupMenus · SetupStep
   ├─ gui/        Menu · PaginatedMenu · ItemBuilder · ChatPrompt
   ├─ admin/      AdminMenus (arena list, live config editor)
   ├─ shop/       ShopService · ShopMenu (quick-buy) · UpgradeMenu
   ├─ db/         DatabaseManager · StatsService · RedisSync · Stat · PlayerProfile
   ├─ listener/   GameListener · PlayerListener
   ├─ command/    BedwarsCommand (Brigadier)
   └─ hook/       BedwarsExpansion (PAPI) · RewardService (Vault)`;

function Section({ id, eyebrow, title, children }: { id: string; eyebrow: string; title: string; children: ReactNode }) {
  return (
    <section id={id} className="mx-auto w-full max-w-6xl px-6 py-14">
      <p className="text-xs font-semibold uppercase tracking-[0.18em] text-amber-400">{eyebrow}</p>
      <h2 className="mt-2 text-3xl font-semibold text-white">{title}</h2>
      <div className="mt-6">{children}</div>
    </section>
  );
}

export default function Page() {
  const files = loadPluginFiles();
  const javaFiles = files.filter((f) => f.path.endsWith(".java"));

  return (
    <main>
      <header className="relative overflow-hidden border-b border-white/10">
        <div className="absolute inset-0 bg-[radial-gradient(60%_80%_at_20%_0%,rgba(251,146,60,0.18),transparent),radial-gradient(50%_70%_at_90%_10%,rgba(239,68,68,0.15),transparent)]" />
        <div className="relative mx-auto max-w-6xl px-6 pb-16 pt-20">
          <p className="inline-flex rounded-full border border-amber-400/30 bg-amber-400/10 px-3 py-1 text-xs font-medium text-amber-300">
            Paper · Purpur · Folia — 1.21.x → 26.x
          </p>
          <h1 className="mt-5 max-w-3xl text-5xl font-semibold leading-[1.05] text-white md:text-6xl">
            NexusBedwars <span className="bg-gradient-to-r from-amber-300 to-red-400 bg-clip-text text-transparent">architecture blueprint</span>
          </h1>
          <p className="mt-5 max-w-2xl text-lg text-slate-400">
            A modular, regionised-threading-safe Bedwars plugin. The complete Java source lives in <code className="rounded bg-white/10 px-1.5 py-0.5 text-sm">/plugin</code> and is browsable below.
            It compiles cleanly against the Paper 1.21.4 API.
          </p>
          <dl className="mt-10 grid max-w-2xl grid-cols-2 gap-4 sm:grid-cols-4">
            {[
              [String(javaFiles.length), "Java classes"],
              [totalLines(files).toLocaleString(), "lines"],
              ["3", "SQL backends"],
              ["0", "BukkitScheduler calls"],
            ].map(([v, l]) => (
              <div key={l} className="rounded-xl border border-white/10 bg-white/5 p-4">
                <dt className="text-2xl font-semibold text-white">{v}</dt>
                <dd className="text-xs text-slate-400">{l}</dd>
              </div>
            ))}
          </dl>
          <nav className="mt-8 flex flex-wrap gap-2 text-sm">
            {["threading", "state-machine", "config", "wizard", "database", "integrations", "source"].map((a) => (
              <a key={a} href={`#${a}`} className="rounded-full border border-white/10 px-3 py-1 text-slate-300 transition hover:border-amber-400/50 hover:text-amber-200">
                #{a}
              </a>
            ))}
          </nav>
        </div>
      </header>

      <Section id="threading" eyebrow="Folia abstraction layer" title="One scheduler facade, four thread-ownership rules">
        <p className="max-w-3xl text-slate-400">
          Paper ships the region/entity/global/async scheduler API on every server. On Paper they execute on the main thread, on Folia on the owning region thread – so the plugin never
          branches on “is this Folia?” and never touches the legacy <code>BukkitScheduler</code>. Cross-region moves use <code>teleportAsync</code>; block rollback is dispatched per chunk.
        </p>
        <div className="mt-6 grid gap-4 md:grid-cols-2">
          {threads.map(([name, api, desc]) => (
            <div key={name} className="rounded-xl border border-white/10 bg-white/[0.03] p-5">
              <div className="flex items-baseline justify-between">
                <h3 className="font-semibold text-white">{name}</h3>
                <code className="text-xs text-amber-300">{api}</code>
              </div>
              <p className="mt-2 text-sm text-slate-400">{desc}</p>
            </div>
          ))}
        </div>
        <div className="mt-4 rounded-xl border border-white/10 bg-white/[0.03] p-5 text-sm text-slate-400">
          <b className="text-slate-200">Version compatibility:</b> pure Paper API, no NMS. Every enum→registry rename between 1.21 and 26.x (enchantments, attributes, effects, particles) is isolated in{" "}
          <code>Compat</code> by looking things up with stable resource keys. Libraries (HikariCP, JDBC drivers, Jedis) are fetched by Paper’s library loader, so the jar stays tiny.
        </div>
      </Section>

      <Section id="state-machine" eyebrow="Core" title="Arena state machine">
        <div className="flex flex-wrap items-center gap-2">
          {states.map((s, i) => (
            <div key={s} className="flex items-center gap-2">
              <span className={`rounded-lg border px-4 py-2 text-sm font-semibold ${s === "RUNNING" ? "border-red-400/50 bg-red-400/10 text-red-200" : "border-white/15 bg-white/5 text-slate-200"}`}>
                {s}
              </span>
              {i < states.length - 1 && <span className="text-slate-600">→</span>}
            </div>
          ))}
          <span className="text-slate-600">→ WAITING</span>
        </div>
        <ul className="mt-6 grid gap-3 text-sm text-slate-400 md:grid-cols-3">
          <li className="rounded-xl border border-white/10 bg-white/[0.03] p-4"><b className="text-slate-200">Lock-free.</b> Transitions are compare-and-set, so a countdown tick and a region thread racing for the last bed can never both win.</li>
          <li className="rounded-xl border border-white/10 bg-white/[0.03] p-4"><b className="text-slate-200">Validated.</b> <code>ArenaState#canTransitionTo</code> rejects illegal jumps; an admin may always force DISABLED.</li>
          <li className="rounded-xl border border-white/10 bg-white/[0.03] p-4"><b className="text-slate-200">Zero-lag rollback.</b> A per-chunk change journal restores only touched blocks, in sliced batches through region schedulers – O(changes), not O(arena).</li>
        </ul>
      </Section>

      <Section id="config" eyebrow="Triple-layer configuration" title="GUI, commands and YAML – one live store">
        <div className="grid gap-4 md:grid-cols-3">
          {layers.map((l) => (
            <div key={l.title} className="rounded-xl border border-white/10 bg-white/[0.03] p-5">
              <h3 className="font-semibold text-white">{l.title}</h3>
              <p className="mt-2 text-sm text-slate-400">{l.body}</p>
            </div>
          ))}
        </div>
        <p className="mt-4 max-w-3xl text-sm text-slate-400">
          Reads are served from an immutable flattened snapshot swapped atomically, so async DB code, region threads and PlaceholderAPI can read any setting lock-free; writes update the snapshot
          instantly and persist asynchronously with comments preserved.
        </p>
      </Section>

      <Section id="wizard" eyebrow="Arena administration" title="Setup wizard with validation checklist">
        <ol className="grid gap-3 md:grid-cols-2">
          {wizard.map(([name, desc], i) => (
            <li key={name} className="flex gap-4 rounded-xl border border-white/10 bg-white/[0.03] p-4">
              <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-amber-400/15 text-sm font-semibold text-amber-300">{i + 1}</span>
              <div>
                <h3 className="font-semibold text-white">{name}</h3>
                <p className="text-sm text-slate-400">{desc}</p>
              </div>
            </li>
          ))}
        </ol>
        <p className="mt-4 text-sm text-slate-500">
          Source: <code>setup/SetupWizard.java</code> (controller, wand, session) and <code>setup/SetupMenus.java</code> (all screens) · <code>model/ValidationReport.java</code> (the checklist).
        </p>
      </Section>

      <Section id="database" eyebrow="Persistence" title="Async multi-backend database manager">
        <div className="grid gap-4 md:grid-cols-2">
          <div className="rounded-xl border border-white/10 bg-white/[0.03] p-5 text-sm text-slate-400">
            <ul className="list-disc space-y-2 pl-5">
              <li>SQLite (WAL), MySQL/MariaDB and PostgreSQL behind <b className="text-slate-200">HikariCP</b>.</li>
              <li>Every call returns a <code>CompletableFuture</code> on a dedicated DB pool – no JDBC on server threads.</li>
              <li>Versioned migrations; portable ANSI DDL; dialect only where unavoidable (auto-increment).</li>
              <li>Delta writes (<code>col = col + ?</code>) → multiple servers never lose updates; failed flushes are restored and retried.</li>
              <li>Redis Pub/Sub fans persisted changes out so other servers refresh cached profiles instantly.</li>
            </ul>
          </div>
          <pre className="overflow-auto rounded-xl border border-white/10 bg-[#0b0f17] p-5 text-xs leading-5 text-slate-300">{`bw_players   uuid PK · name · wins · losses · kills · deaths
             final_kills · final_deaths · beds_broken
             games_played · winstreak · best_winstreak · elo
             first_seen · last_seen
bw_quickbuy  uuid PK · layout
bw_matches   id PK · server_id · arena · mode · winner_team
             duration_s · started_at · ended_at
bw_schema_version  version`}</pre>
        </div>
      </Section>

      <Section id="integrations" eyebrow="Integrations" title="PlaceholderAPI, Vault, MiniMessage">
        <div className="overflow-hidden rounded-xl border border-white/10">
          <table className="w-full text-left text-sm">
            <tbody>
              {placeholders.map(([ph, d]) => (
                <tr key={ph} className="border-b border-white/5 last:border-0">
                  <td className="bg-white/[0.03] px-4 py-3 font-mono text-xs text-amber-200">{ph}</td>
                  <td className="px-4 py-3 text-slate-400">{d}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-4 max-w-3xl text-sm text-slate-400">
          Placeholders read only in-memory caches, so scoreboards, tablists and DecentHolograms / FancyHolograms can poll them at any rate. All chat, titles, action bars and GUI lore use
          MiniMessage; Vault payouts are optional and loaded lazily.
        </p>
      </Section>

      <Section id="source" eyebrow="Complete source" title="Project structure & code explorer">
        <pre className="mb-6 overflow-auto rounded-xl border border-white/10 bg-[#0b0f17] p-5 text-xs leading-5 text-slate-300">{tree}</pre>
        <CodeExplorer files={files} />
        <p className="mt-4 text-sm text-slate-500">
          Build: <code>cd plugin &amp;&amp; gradle build</code> → <code>build/libs/NexusBedwars-1.0.0.jar</code>. Drop it into <code>plugins/</code> on Paper, Purpur or Folia (Java 21+).
        </p>
      </Section>
    </main>
  );
}
