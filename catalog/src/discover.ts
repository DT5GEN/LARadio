// Finds candidate streams for stations that have no working stream, using the Radio Browser API.
// Also probes alternative StreamTheWorld mounts for KPWR.
// Writes discover-report.md (human readable) and discover-report.json next to curated.yaml.
// Usage: npm run discover
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import YAML from "yaml";
import { checkStream } from "./check.js";

const UA = "SantaMonicaRadio/0.1 (catalog-discover)";
const HOSTS = ["de1", "de2", "fr1", "nl1", "at1"].map(h => `https://${h}.api.radio-browser.info`);
const STW = "https://playerservices.streamtheworld.com/api/livestream-redirect/";

interface StationDef { id: string; name: string; callsign?: string; frequency?: string; streams: { url: string }[]; }
interface RB {
  stationuuid: string; name: string; url: string; url_resolved: string; codec: string; bitrate: number;
  state: string; tags: string; homepage: string; hls: number; lastcheckok: number; geo_lat: number | null; geo_long: number | null;
}

async function rb(path: string): Promise<RB[]> {
  for (const host of HOSTS) {
    try {
      const r = await fetch(host + path, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(15000) });
      if (r.ok) return (await r.json()) as RB[];
    } catch { /* try next mirror */ }
  }
  throw new Error("All Radio Browser mirrors failed for " + path);
}

const dir = dirname(fileURLToPath(import.meta.url));
const stations: StationDef[] = YAML.parse(readFileSync(join(dir, "..", "curated.yaml"), "utf8"));

const report: any = { generatedAt: new Date().toISOString(), kpwrMounts: [], stations: {}, laSample: 0 };
let md = `# Discover report\n\nGenerated: ${report.generatedAt}\n\n`;

// 1) KPWR: probe StreamTheWorld mount guesses
const mounts = ["KPWRFM", "KPWRFMAAC", "KPWRFM_SC", "KPWRFMAAC_SC", "KPWR_FM", "KPWRFM.mp3", "KPWRFMAAC.aac", "KPWRFM_SC.mp3", "KPWRFMAAC_SC.aac", "KPWRFM_AAC", "KPWRHD2"];
md += `## POWER 106: StreamTheWorld mount probes\n\n| URL | Result | Content-Type | kbps | ms | Detail |\n|---|---|---|---|---|---|\n`;
for (const m of mounts) {
  const url = STW + m;
  const c = await checkStream("kpwr", url);
  report.kpwrMounts.push(c);
  md += `| ${url} | ${c.ok ? "OK" : "FAIL"} | ${c.contentType ?? "-"} | ${c.bitrate ?? "-"} | ${c.latencyMs ?? "-"} | ${c.ok ? "" : c.error ?? ""} |\n`;
  console.log(c.ok ? "OK  " : "FAIL", url, c.error ?? "");
}

// 2) Radio Browser: search by name for every station without a working-looking stream (all of them without streams, plus kpwr)
const wanted = stations.filter(s => s.streams.length === 0 || s.id === "kpwr");
for (const s of wanted) {
  const queries = [s.callsign, s.name.replace(/\s*\(.*\)/, "")].filter(Boolean) as string[];
  const seen = new Map<string, RB>();
  for (const q of queries) {
    try {
      const list = await rb(`/json/stations/search?name=${encodeURIComponent(q)}&countrycode=US&hidebroken=true&limit=40&order=clickcount&reverse=true`);
      for (const x of list) seen.set(x.stationuuid, x);
    } catch (e: any) { console.log("RB error", q, e.message); }
  }
  // keep California or unknown state; prefer LA-ish
  const cands = [...seen.values()].filter(x => !x.state || /californ/i.test(x.state)).slice(0, 6);
  md += `\n## ${s.name} (${s.callsign ?? s.id}, ${s.frequency ?? "-"})\n\n`;
  report.stations[s.id] = [];
  if (!cands.length) { md += "No candidates found in Radio Browser.\n"; continue; }
  md += `| Name | Check | Content-Type | kbps | codec | State | URL |\n|---|---|---|---|---|---|---|\n`;
  for (const x of cands) {
    const url = x.url_resolved || x.url;
    const c = await checkStream(s.id, url);
    report.stations[s.id].push({ rb: x, check: c });
    md += `| ${x.name} | ${c.ok ? "OK" : "FAIL"} | ${c.contentType ?? "-"} | ${c.bitrate ?? x.bitrate ?? "-"} | ${x.codec} | ${x.state || "-"} | ${url} |\n`;
    console.log(s.id, c.ok ? "OK  " : "FAIL", x.name, url);
  }
}

// 3) Overview of LA-area stations in Radio Browser (for the genre catalog later)
try {
  const ca = await rb(`/json/stations/search?countrycode=US&state=California&hidebroken=true&limit=1000&order=clickcount&reverse=true`);
  const la = ca.filter(x => x.geo_lat && x.geo_long && Math.hypot(x.geo_lat - 34.05, x.geo_long + 118.25) < 0.8);
  report.laSample = la.length;
  md += `\n## LA-area stations in Radio Browser (${la.length} of ${ca.length} California)\n\n| Name | Tags | Codec | kbps | Homepage |\n|---|---|---|---|---|\n`;
  for (const x of la.slice(0, 120)) md += `| ${x.name} | ${x.tags.split(",").slice(0, 4).join(", ")} | ${x.codec} | ${x.bitrate} | ${x.homepage} |\n`;
  report.la = la.slice(0, 300);
} catch (e: any) { md += `\nLA overview failed: ${e.message}\n`; }

writeFileSync(join(dir, "..", "discover-report.md"), md);
writeFileSync(join(dir, "..", "discover-report.json"), JSON.stringify(report, null, 1));
console.log("\nDone. Report: catalog/discover-report.md");
