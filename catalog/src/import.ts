// Generates catalog/auto.yaml: LA-area stations from Radio Browser that are not already in curated.yaml.
// Runs in GitHub Actions before build (and locally: npm run import). build.ts then checks every stream.
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import YAML from "yaml";

const UA = "SantaMonicaRadio/0.1 (catalog-import)";
const HOSTS = ["de1", "de2", "fr1", "nl1", "at1"].map(h => `https://${h}.api.radio-browser.info`);
const MAX_AUTO = 70;
const LA = { lat: 34.05, lon: -118.25, maxDeg: 0.9 };
const LA_NAME = /los angeles|santa monica|hollywood|long beach|pasadena|burbank|glendale|compton|inglewood|anaheim|orange county|san fernando|torrance|culver city|\bL\.?A\.?\b/i;

interface RB {
  stationuuid: string; name: string; url: string; url_resolved: string; codec: string; bitrate: number;
  state: string; tags: string; homepage: string; hls: number; lastcheckok: number;
  geo_lat: number | null; geo_long: number | null; clickcount: number;
}

async function rb(path: string): Promise<RB[]> {
  for (const host of HOSTS) {
    try {
      const r = await fetch(host + path, { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(20000) });
      if (r.ok) return (await r.json()) as RB[];
    } catch { /* next mirror */ }
  }
  throw new Error("Radio Browser unreachable");
}

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, "");
const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 24);

function genreOf(tags: string, name: string): string {
  const t = (tags + " " + name).toLowerCase();
  const has = (...w: string[]) => w.some(x => t.includes(x));
  if (has("classic hip", "oldschool", "old school", "90s hip")) return "classic-hiphop";
  if (has("hip hop", "hip-hop", "hiphop", "rap", "trap")) return "hiphop";
  if (has("r&b", "rnb", "soul", "funk", "motown", "urban")) return "rnb";
  if (has("latin", "reggaeton", "spanish", "mexican", "salsa", "banda", "regional", "norteno", "cumbia", "música", "musica")) return "latin";
  if (has("jazz", "blues", "bebop")) return "jazz";
  if (has("classical", "opera", "orchestra")) return "classical";
  if (has("country")) return "country";
  if (has("alternative", "indie", "punk", "grunge", "new wave")) return "alternative";
  if (has("rock", "metal")) return "rock";
  if (has("electronic", "edm", "house", "techno", "dance", "trance", "dubstep", "lounge", "chill")) return "electronic";
  if (has("news", "talk", "sports", "politic")) return "news";
  if (has("oldies", "classic hits", "80s", "70s", "90s", "retro", "disco", "nostalgia")) return "classic-hits";
  if (has("adult contemporary", "easy listening", "soft", "beautiful music")) return "adult";
  if (has("top 40", "pop", "hits", "hot ac", "chr")) return "top40";
  return "eclectic";
}

const dir = dirname(fileURLToPath(import.meta.url));
const curated: any[] = YAML.parse(readFileSync(join(dir, "..", "curated.yaml"), "utf8"));
const knownCallsigns = curated.map(s => (s.callsign ?? "").toLowerCase()).filter((c: string) => c.length >= 3);
const knownNames = new Set(curated.map(s => norm(s.name)));
const knownUrls = new Set(curated.flatMap(s => (s.streams ?? []).map((x: any) => x.url.replace(/^https?:\/\//, ""))));

const byUuid = new Map<string, RB>();
for (const q of [
  "/json/stations/search?countrycode=US&state=California&hidebroken=true&limit=1500&order=clickcount&reverse=true",
  "/json/stations/search?name=los%20angeles&countrycode=US&hidebroken=true&limit=300&order=clickcount&reverse=true",
]) {
  try { for (const x of await rb(q)) byUuid.set(x.stationuuid, x); } catch (e: any) { console.warn("query failed:", e.message); }
}
console.log(`Radio Browser returned ${byUuid.size} candidate stations`);

const isLA = (x: RB) =>
  (x.geo_lat != null && x.geo_long != null && Math.hypot(x.geo_lat - LA.lat, x.geo_long - LA.lon) < LA.maxDeg) ||
  LA_NAME.test(`${x.name} ${x.state}`);

const picked = [...byUuid.values()]
  .filter(x => x.lastcheckok === 1 && /mp3|aac/i.test(x.codec) && isLA(x))
  .filter(x => {
    const n = norm(x.name);
    const url = (x.url_resolved || x.url).replace(/^https?:\/\//, "");
    if (knownUrls.has(url)) return false;
    if (knownNames.has(n)) return false;
    return !knownCallsigns.some((cs: string) => n.includes(cs));
  })
  .sort((a, b) => b.clickcount - a.clickcount)
  .slice(0, MAX_AUTO);

const usedIds = new Set<string>();
const out = picked.map(x => {
  const short = x.name.split(/\s+-\s+/)[0].trim();
  let id = "rb-" + (slug(short) || x.stationuuid.slice(0, 8));
  for (let i = 2; usedIds.has(id); i++) id = `rb-${slug(short)}-${i}`;
  usedIds.add(id);
  const freq = /(\d{2,3}\.\d)\s*(FM|AM)?/i.exec(x.name);
  const tags = x.tags.split(",").map(t => t.trim()).filter(Boolean).slice(0, 3).join(", ");
  const url = x.url_resolved || x.url;
  const q = x.bitrate >= 96 ? "high" : x.bitrate >= 48 ? "medium" : x.bitrate > 0 ? "low" : "medium";
  return {
    id, name: short, frequency: freq ? `${freq[1]} ${(freq[2] ?? "FM").toUpperCase()}` : "WEB",
    genres: [genreOf(x.tags, x.name)], homepage: x.homepage || undefined, description: tags || undefined,
    source: "radiobrowser", radioBrowserUuid: x.stationuuid,
    streams: [{ url, quality: q, bitrate: x.bitrate || undefined }],
  };
});

writeFileSync(join(dir, "..", "auto.yaml"), "# generated by import.ts - do not edit\n" + YAML.stringify(out));
console.log(`auto.yaml: ${out.length} stations`);
const byGenre: Record<string, number> = {};
for (const s of out) byGenre[s.genres[0]] = (byGenre[s.genres[0]] ?? 0) + 1;
console.log(byGenre);
