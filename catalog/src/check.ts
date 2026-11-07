// Checks every stream in curated.yaml: reachable? content-type, bitrate, ICY support, time to first byte.
// Usage: npm run check
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import YAML from "yaml";

const UA = "SantaMonicaRadio/0.1 (catalog-check)";
const TIMEOUT_MS = 8000;
const CONCURRENCY = 8;

interface StreamDef { url: string; quality?: string }
interface StationDef {
  id: string; name: string; frequency?: string; genres: string[];
  streams: StreamDef[]; officialPlayer?: string; verified?: boolean;
}
export interface StreamCheck {
  stationId: string; url: string; ok: boolean; status?: number;
  contentType?: string; bitrate?: number; icy: boolean; latencyMs?: number; error?: string;
}

export async function checkStream(stationId: string, url: string): Promise<StreamCheck> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  const t0 = Date.now();
  const res: StreamCheck = { stationId, url, ok: false, icy: false };
  try {
    const r = await fetch(url, {
      headers: { "Icy-MetaData": "1", "User-Agent": UA },
      signal: ctrl.signal,
      redirect: "follow",
    });
    res.status = r.status;
    res.contentType = r.headers.get("content-type") ?? undefined;
    const br = r.headers.get("icy-br");
    if (br) res.bitrate = parseInt(br, 10) || undefined;
    res.icy = r.headers.has("icy-metaint");
    if (!r.ok || !r.body) throw new Error(`HTTP ${r.status}`);
    // read the first chunk of audio to prove data actually flows
    const reader = r.body.getReader();
    const first = await reader.read();
    res.latencyMs = Date.now() - t0;
    await reader.cancel().catch(() => {});
    const ct = (res.contentType ?? "").toLowerCase();
    const looksAudio = ct.startsWith("audio/") || ct.includes("mpegurl") || ct.includes("ogg") || ct.includes("aac");
    res.ok = !!first.value && first.value.length > 0 && looksAudio;
    if (!res.ok) res.error = looksAudio ? "no data" : `not audio (${ct || "no content-type"})`;
  } catch (e: any) {
    res.error = e?.name === "AbortError" ? `timeout ${TIMEOUT_MS}ms` : (e?.cause?.code ?? e?.message ?? String(e));
  } finally {
    clearTimeout(timer);
    ctrl.abort();
  }
  return res;
}

async function pool<T, R>(items: T[], n: number, fn: (x: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let i = 0;
  await Promise.all(Array.from({ length: n }, async () => {
    while (i < items.length) { const k = i++; out[k] = await fn(items[k]); }
  }));
  return out;
}

async function main() {
  const dir = dirname(fileURLToPath(import.meta.url));
  const stations: StationDef[] = YAML.parse(readFileSync(join(dir, "..", "curated.yaml"), "utf8"));
  const jobs = stations.flatMap(s => s.streams.map(st => ({ s, url: st.url })));
  console.log(`Checking ${jobs.length} streams for ${stations.length} stations...\n`);
  const results = await pool(jobs, CONCURRENCY, j => checkStream(j.s.id, j.url));

  const pad = (x: string, n: number) => x.length >= n ? x.slice(0, n) : x + " ".repeat(n - x.length);
  console.log(pad("STATION", 10), pad("RESULT", 7), pad("CONTENT-TYPE", 26), pad("KBPS", 5), pad("ICY", 4), pad("MS", 6), "DETAIL");
  for (const r of results) {
    console.log(pad(r.stationId, 10), pad(r.ok ? "OK" : "FAIL", 7), pad(r.contentType ?? "-", 26),
      pad(r.bitrate ? String(r.bitrate) : "-", 5), pad(r.icy ? "yes" : "no", 4),
      pad(r.latencyMs ? String(r.latencyMs) : "-", 6), r.ok ? "" : (r.error ?? ""));
  }
  console.log("\nStations without any stream (official player only):",
    stations.filter(s => s.streams.length === 0).map(s => s.id).join(", ") || "none");
  const bad = results.filter(r => !r.ok).length;
  console.log(`\n${results.length - bad} OK, ${bad} FAIL`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
