import fs from "node:fs";
import path from "node:path";

export type PluginFile = {
  path: string;
  content: string;
  lines: number;
};

const ROOT = path.join(process.cwd(), "plugin");
const ALLOWED = new Set([".java", ".kts", ".yml", ".md"]);

function walk(dir: string, out: PluginFile[]) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "build" || entry.name === ".gradle" || entry.name === "node_modules") continue;
      walk(full, out);
    } else if (ALLOWED.has(path.extname(entry.name))) {
      const content = fs.readFileSync(full, "utf8");
      out.push({
        path: path.relative(ROOT, full).split(path.sep).join("/"),
        content,
        lines: content.split("\n").length,
      });
    }
  }
}

/** Reads every source file of the Java plugin (build time, server side only). */
export function loadPluginFiles(): PluginFile[] {
  const out: PluginFile[] = [];
  if (fs.existsSync(ROOT)) walk(ROOT, out);
  // build files first, then resources, then java sorted by path
  const rank = (p: string) => (p.endsWith(".kts") ? 0 : p.includes("resources") ? 1 : 2);
  return out.sort((a, b) => rank(a.path) - rank(b.path) || a.path.localeCompare(b.path));
}

export function totalLines(files: PluginFile[]) {
  return files.reduce((n, f) => n + f.lines, 0);
}
