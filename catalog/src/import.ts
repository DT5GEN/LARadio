// Generates catalog/auto.yaml: stations from Radio Browser (LA area, Spain, Sweden) not already in the curated lists.
// Runs in GitHub Actions before build (and locally: npm run import). build.ts then checks every stream.
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import YAML from "yaml";
import { existsSync, readdirSync } from "node:fs";
import { REGIONS } from "./regions.js";

const UA = "SantaMonicaRadio/0.1 (catalog-import)";
const HOSTS = ["de1", "de2", "fr1", "nl1", "at1"].map(h => `https://${h}.api.radio-browser.info`);
const MAX_AUTO: Record<string, number> = { la: 70, es: 60, se: 50 };
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

function genreOf(tags: string, name: string, region: string): string {
  const t = (tags + " " + name).toLowerCase();
  const has = (...w: string[]) => w.some(x => t.includes(x));
  if (has("flamenco", "folk", "folklore", "copla")) return "folk";
  if (has("dansband", "schlager")) return "schlager";
  if (region !== "la" && has("noticias", "actualidad", "deportes", "nyheter", "nacional")) return "news";
  if (has("classic hip", "oldschool", "old school", "90s hip")) return "classic-hiphop";
  if (has("hip hop", "hip-hop", "hiphop", "rap", "trap")) return "hiphop";
  if (has("r&b", "rnb", "soul", "funk", "motown", "urban")) return "rnb";
  if (region === "la"
    ? has("latin", "reggaeton", "spanish", "mexican", "salsa", "banda", "regional", "norteno", "cumbia", "música", "musica")
    : has("latin", "reggaeton", "salsa", "bachata", "cumbia", "latino")) return "latin";
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
const loadCurated = (file: string): any[] => {
  const path = join(dir, "..", file);
  return existsSync(path) ? (YAML.parse(readFileSync(path, "utf8")) ?? []) : [];
};

const QUERIES: Record<string, string[]> = {
  la: [
    "/json/stations/search?countrycode=US&state=California&hidebroken=true&limit=1500&order=clickcount&reverse=true",
    "/json/stations/search?name=los%20angeles&countrycode=US&hidebroken=true&limit=300&order=clickcount&reverse=true",
  ],
  es: ["/json/stations/search?countrycode=ES&hidebroken=true&limit=800&order=clickcount&reverse=true"],
  se: ["/json/stations/search?countrycode=SE&hidebroken=true&limit=500&order=clickcount&reverse=true"],
};

const isLA = (x: RB) =>
  (x.geo_lat != null && x.geo_long != null && Math.hypot(x.geo_lat - LA.lat, x.geo_long - LA.lon) < LA.maxDeg) ||
  LA_NAME.test(`${x.name} ${x.state}`);

// ---- extra sources -------------------------------------------------------------------------
// 1) TDTChannels radio list (Spain, free/collaborative, https://github.com/LaQuay/TDTChannels)
// 2) own .m3u/.m3u8 files in catalog/imports/<region>/
interface Extra { name: string; urls: string[]; tags: string; homepage?: string; source: string }

async function tdtSpain(): Promise<Extra[]> {
  try {
    const r = await fetch("https://www.tdtchannels.com/lists/radio.json", { headers: { "User-Agent": UA }, signal: AbortSignal.timeout(30000) });
    if (!r.ok) throw new Error("HTTP " + r.status);
    const j: any = await r.json();
    const spain = (j.countries ?? []).find((c: any) => /spain|españa/i.test(c.name));
    const ORDER: Record<string, number> = { aac: 0, mp3: 1, stream: 2, m3u8: 3 };
    const out: Extra[] = [];
    for (const ambit of spain?.ambits ?? []) {
      if (!["Populares", "Musicales", "Deportivas"].includes(ambit.name)) continue;
      const tag = ambit.name === "Deportivas" ? "sports" : ambit.name === "Populares" ? "talk" : "";
      for (const ch of ambit.channels ?? []) {
        const urls = (ch.options ?? [])
          .filter((o: any) => o.url && /^https?:\/\//.test(o.url) && !o.geo2)
          .sort((a: any, b: any) => (ORDER[a.format] ?? 9) - (ORDER[b.format] ?? 9))
          .map((o: any) => o.url as string)
          .slice(0, 3);
        if (urls.length) out.push({ name: ch.name, urls, tags: tag, homepage: ch.web || undefined, source: "tdtchannels" });
      }
    }
    console.log(`[es] TDTChannels: ${out.length} stations`);
    return out;
  } catch (e: any) {
    console.warn("[es] TDTChannels failed:", e.message);
    return [];
  }
}

function m3uFiles(regionId: string): Extra[] {
  const d = join(dir, "..", "imports", regionId);
  if (!existsSync(d)) return [];
  const out: Extra[] = [];
  for (const f of readdirSync(d).filter(f => /\.m3u8?$/i.test(f))) {
    const lines = readFileSync(join(d, f), "utf8").split(/\r?\n/);
    let title = "";
    for (const line of lines) {
      if (line.startsWith("#EXTINF")) title = line.slice(line.lastIndexOf(",") + 1).trim();
      else if (/^https?:\/\//.test(line.trim())) { out.push({ name: title || line.trim(), urls: [line.trim()], tags: "", source: "m3u:" + f }); title = ""; }
    }
  }
  console.log(`[${regionId}] imports/*.m3u: ${out.length} stations`);
  return out;
}

const brandOf = (name: string) => name.toLowerCase().split(/\s+/)[0];

const all: any[] = [];
for (const region of REGIONS) {
  const curated = loadCurated(region.file);
  const knownCallsigns = curated.map(s => (s.callsign ?? "").toLowerCase()).filter((c: string) => c.length >= 3);
  const knownNames = new Set(curated.map(s => norm(s.name)));
  const knownUrls = new Set(curated.flatMap(s => (s.streams ?? []).map((x: any) => x.url.replace(/^https?:\/\//, ""))));

  const byUuid = new Map<string, RB>();
  for (const q of QUERIES[region.id] ?? []) {
    try { for (const x of await rb(q)) byUuid.set(x.stationuuid, x); } catch (e: any) { console.warn(`[${region.id}] query failed:`, e.message); }
  }
  console.log(`[${region.id}] Radio Browser returned ${byUuid.size} candidate stations`);

  const picked = [...byUuid.values()]
    .filter(x => x.lastcheckok === 1 && /mp3|aac/i.test(x.codec) && (region.id !== "la" || isLA(x)))
    .filter(x => {
      const n = norm(x.name);
      const url = (x.url_resolved || x.url).replace(/^https?:\/\//, "");
      if (knownUrls.has(url)) return false;
      if (knownNames.has(n)) return false;
      return !knownCallsigns.some((cs: string) => n.includes(cs));
    })
    .sort((a, b) => b.clickcount - a.clickcount)
    .slice(0, MAX_AUTO[region.id] ?? 50);

  const usedIds = new Set<string>();
  const prefix = region.id === "la" ? "rb-" : `rb-${region.id}-`;
  for (const x of picked) {
    const short = x.name.split(/\s+-\s+/)[0].trim();
    let id = prefix + (slug(short) || x.stationuuid.slice(0, 8));
    for (let i = 2; usedIds.has(id); i++) id = `${prefix}${slug(short)}-${i}`;
    usedIds.add(id);
    const freq = /(\d{2,3}\.\d)\s*(FM|AM)?/i.exec(x.name);
    const tags = x.tags.split(",").map(t => t.trim()).filter(Boolean).slice(0, 3).join(", ");
    const url = x.url_resolved || x.url;
    const q = x.bitrate >= 96 ? "high" : x.bitrate >= 48 ? "medium" : x.bitrate > 0 ? "low" : "medium";
    all.push({
      id, region: region.id, name: short, frequency: freq ? `${freq[1]} ${(freq[2] ?? "FM").toUpperCase()}` : "WEB",
      genres: [genreOf(x.tags, x.name, region.id)], homepage: x.homepage || undefined, description: tags || undefined,
      source: "radiobrowser", radioBrowserUuid: x.stationuuid,
      streams: [{ url, quality: q, bitrate: x.bitrate || undefined }],
    });
  }

  // extras: TDTChannels (es) + own m3u files; skip brands that already flood the list (e.g. 20 sub-channels of one station)
  const extras = [...(region.id === "es" ? await tdtSpain() : []), ...m3uFiles(region.id)];
  const brandCount = new Map<string, number>();
  for (const e of extras) {
    const n = norm(e.name);
    if (knownNames.has(n) || all.some(a => a.region === region.id && norm(a.name) === n)) continue;
    if (e.urls.every(u => knownUrls.has(u.replace(/^https?:\/\//, "")))) continue;
    const b = brandOf(e.name);
    if ((brandCount.get(b) ?? 0) >= 3) continue;
    brandCount.set(b, (brandCount.get(b) ?? 0) + 1);
    let genre = genreOf(e.tags, e.name, region.id);
    if (/radiol[eé]|copla/i.test(e.name)) genre = "folk";
    else if (/\bdial\b/i.test(e.name)) genre = "latin";
    let id = `x-${region.id}-${slug(e.name)}`;
    for (let i = 2; all.some(a => a.id === id); i++) id = `${id}-${i}`;
    all.push({
      id, region: region.id, name: e.name, frequency: "WEB", genres: [genre], homepage: e.homepage, description: e.tags || undefined,
      source: e.source, streams: e.urls.map(u => ({ url: u, quality: "medium" })),
    });
  }
}

// (extras are added inside the region loop above)
writeFileSync(join(dir, "..", "auto.yaml"), "# generated by import.ts - do not edit\n" + YAML.stringify(all));
console.log(`auto.yaml: ${all.length} stations`);
const byRegionGenre: Record<string, number> = {};
for (const s of all) { const k = `${s.region}/${s.genres[0]}`; byRegionGenre[k] = (byRegionGenre[k] ?? 0) + 1; }
console.log(byRegionGenre);
