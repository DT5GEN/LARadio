// Builds dist/stations.json (schemaVersion 1, see docs/CONCEPT.md section 4.3) and dist/report.md
// from curated.yaml by checking every stream. Usage: npm run build
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import YAML from "yaml";
import Ajv from "ajv";
import { checkStream, type StreamCheck } from "./check.js";

const GENRES = [
  { id: "hiphop", name: "Hip-Hop", nameRu: "Хип-хоп", color: "#67ff91" },
  { id: "classic-hiphop", name: "Classic Hip-Hop", nameRu: "Олдскул", color: "#ffd84d" },
  { id: "rnb", name: "R&B / Soul", nameRu: "R&B / Соул", color: "#ff7ab8" },
  { id: "top40", name: "Top 40 / Pop", nameRu: "Поп / Хиты", color: "#5ad1ff" },
  { id: "latin", name: "Latin", nameRu: "Латино", color: "#ff8a3d" },
  { id: "alternative", name: "Alternative", nameRu: "Альтернатива", color: "#b48cff" },
  { id: "rock", name: "Rock", nameRu: "Рок", color: "#ff5a5a" },
  { id: "classic-hits", name: "Classic Hits", nameRu: "Хиты 70–90-х", color: "#e5c18b" },
  { id: "jazz", name: "Jazz", nameRu: "Джаз", color: "#4ca9ff" },
  { id: "classical", name: "Classical", nameRu: "Классика", color: "#d9d9ff" },
  { id: "eclectic", name: "Eclectic & Public", nameRu: "Независимое", color: "#7affd0" },
  { id: "country", name: "Country", nameRu: "Кантри", color: "#d8a35a" },
  { id: "adult", name: "Adult Contemporary", nameRu: "Лёгкая музыка", color: "#ffb0c8" },
  { id: "news", name: "News & Talk", nameRu: "Новости", color: "#a8b7ad" },
].map((g, i) => ({ ...g, order: i + 1 }));

const dir = dirname(fileURLToPath(import.meta.url));
const curated: any[] = YAML.parse(readFileSync(join(dir, "..", "curated.yaml"), "utf8"));

const formatOf = (url: string, ct?: string) => {
  const u = url.toLowerCase(), c = (ct ?? "").toLowerCase();
  if (u.includes(".m3u8") || c.includes("mpegurl")) return "hls";
  if (c.includes("ogg")) return "ogg";
  if (c.includes("aac") || u.endsWith(".aac")) return "aac";
  return "mp3";
};
const classify = (kbps: number | undefined, hint?: string, fmt?: string) => {
  if (fmt === "hls") return "high"; // adaptive
  if (kbps == null) return hint ?? "medium";
  return kbps >= 96 ? "high" : kbps >= 48 ? "medium" : "low";
};

const OFFLINE = process.argv.includes("--offline"); // no network: assume all streams ok (used for the catalog bundled into apps)
const jobs = curated.flatMap(s => (s.streams ?? []).map((st: any) => ({ s, st })));
const results = new Map<string, StreamCheck>();
let idx = 0;
await Promise.all(Array.from({ length: 8 }, async () => {
  while (idx < jobs.length) { const j = jobs[idx++]; results.set(j.st.url, OFFLINE ? ({ stationId: j.s.id, url: j.st.url, ok: true, icy: false, bitrate: j.st.bitrate } as StreamCheck) : await checkStream(j.s.id, j.st.url)); }
}));

const order: Record<string, number> = { high: 0, medium: 1, low: 2 };
const stations: any[] = [];
let md = `# Catalog build report\n\nGenerated: ${new Date().toISOString()}\n\n| Station | Status | Working streams | Notes |\n|---|---|---|---|\n`;
for (const s of curated) {
  const streams = (s.streams ?? []).map((st: any) => {
    const c = results.get(st.url)!;
    const format = formatOf(st.url, c.contentType);
    const bitrate = c.bitrate ?? undefined;
    return {
      url: st.url, format, bitrate: bitrate ?? null, quality: classify(bitrate, st.quality, format),
      https: st.url.startsWith("https:"), icy: c.icy,
      check: { ok: c.ok, at: new Date().toISOString(), latencyMs: c.latencyMs ?? null, contentType: c.contentType ?? null, error: c.ok ? undefined : c.error },
    };
  });
  const alive = streams.filter((x: any) => x.check.ok).sort((a: any, b: any) => order[a.quality] - order[b.quality] || Number(b.https) - Number(a.https));
  const status = alive.length ? "ok" : s.officialPlayer ? "degraded" : "dead";
  md += `| ${s.name} | ${status} | ${alive.length}/${streams.length} | ${streams.filter((x: any) => !x.check.ok).map((x: any) => `${x.url} (${x.check.error})`).join("; ")} |\n`;
  if (status === "dead") continue;
  stations.push({
    id: s.id, name: s.name, callsign: s.callsign ?? null, frequency: s.frequency ?? "WEB", city: s.city ?? "Los Angeles",
    genres: s.genres, description: GENRES.find(g => g.id === s.genres[0])?.name ?? "", logo: s.logo ?? null,
    homepage: s.homepage ?? null, officialPlayer: s.officialPlayer ?? null, source: "curated", radioBrowserUuid: null,
    streams: alive, status,
  });
}
const out = {
  schemaVersion: 1, generatedAt: new Date().toISOString(), bundled: OFFLINE,
  region: { id: "la", name: "Los Angeles", center: [34.05, -118.25] },
  genres: GENRES.filter(g => stations.some(s => s.genres.includes(g.id))),
  stations,
};
const schema = JSON.parse(readFileSync(join(dir, "..", "schema", "stations.schema.json"), "utf8"));
const AjvCtor: any = (Ajv as any).default ?? Ajv; // ajv ships CJS; works under NodeNext
const validate = new AjvCtor({ allErrors: true, strict: false }).compile(schema);
if (!validate(out)) {
  console.error("stations.json does not match schema:", JSON.stringify(validate.errors, null, 2));
  process.exit(1);
}
mkdirSync(join(dir, "..", "dist"), { recursive: true });
writeFileSync(join(dir, "..", "dist", "stations.json"), JSON.stringify(out, null, 2));
writeFileSync(join(dir, "..", "dist", "report.md"), md);
console.log(md);
console.log(`stations.json: ${stations.length} stations, ${out.genres.length} genres`);
