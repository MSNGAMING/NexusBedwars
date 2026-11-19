"use client";

import { useMemo, useState } from "react";

type PluginFile = { path: string; content: string; lines: number };

const DEFAULT_FILE = "src/main/java/dev/nexus/bedwars/setup/SetupWizard.java";

function shortName(p: string) {
  return p.replace("src/main/java/dev/nexus/bedwars/", "").replace("src/main/resources/", "res/");
}

function groupOf(p: string) {
  const s = shortName(p);
  const i = s.lastIndexOf("/");
  return i === -1 ? "(root)" : s.slice(0, i);
}

export default function CodeExplorer({ files }: { files: PluginFile[] }) {
  const [selected, setSelected] = useState(
    files.find((f) => f.path === DEFAULT_FILE)?.path ?? files[0]?.path ?? ""
  );
  const [query, setQuery] = useState("");
  const [copied, setCopied] = useState(false);

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase();
    const map = new Map<string, PluginFile[]>();
    for (const f of files) {
      if (q && !f.path.toLowerCase().includes(q) && !f.content.toLowerCase().includes(q)) continue;
      const g = groupOf(f.path);
      map.set(g, [...(map.get(g) ?? []), f]);
    }
    return [...map.entries()];
  }, [files, query]);

  const current = files.find((f) => f.path === selected);

  async function copy() {
    if (!current) return;
    await navigator.clipboard.writeText(current.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 1400);
  }

  return (
    <div className="grid overflow-hidden rounded-2xl border border-white/10 bg-[#0b0f17] lg:grid-cols-[320px_1fr]">
      <aside className="flex max-h-[720px] flex-col border-b border-white/10 lg:border-b-0 lg:border-r">
        <div className="p-3">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search files & code…"
            className="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-slate-100 outline-none placeholder:text-slate-500 focus:border-amber-400/60"
          />
        </div>
        <nav className="flex-1 overflow-y-auto px-2 pb-3 text-sm">
          {groups.map(([group, list]) => (
            <div key={group} className="mb-3">
              <p className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-slate-500">{group}</p>
              {list.map((f) => (
                <button
                  key={f.path}
                  onClick={() => setSelected(f.path)}
                  className={`flex w-full items-center justify-between rounded-md px-2 py-1.5 text-left transition ${
                    f.path === selected ? "bg-amber-400/15 text-amber-200" : "text-slate-300 hover:bg-white/5"
                  }`}
                >
                  <span className="truncate">{f.path.split("/").pop()}</span>
                  <span className="ml-2 shrink-0 text-xs text-slate-500">{f.lines}</span>
                </button>
              ))}
            </div>
          ))}
          {groups.length === 0 && <p className="px-2 text-slate-500">No matches.</p>}
        </nav>
      </aside>

      <section className="min-w-0">
        <header className="flex items-center justify-between gap-3 border-b border-white/10 px-4 py-2.5">
          <p className="truncate font-mono text-xs text-slate-400">plugin/{current?.path}</p>
          <button
            onClick={copy}
            className="shrink-0 rounded-md bg-white/10 px-3 py-1 text-xs font-medium text-slate-100 transition hover:bg-white/20"
          >
            {copied ? "Copied ✓" : "Copy"}
          </button>
        </header>
        <pre className="max-h-[672px] overflow-auto p-4 text-[12.5px] leading-5 text-slate-200">
          <code>
            {current?.content.split("\n").map((line, i) => (
              <div key={i} className="flex">
                <span className="mr-4 inline-block w-9 shrink-0 select-none text-right text-slate-600">{i + 1}</span>
                <span className="whitespace-pre">{line || " "}</span>
              </div>
            ))}
          </code>
        </pre>
      </section>
    </div>
  );
}
