// Second discovery round: URL pattern guesses for stations that Radio Browser name search missed,
// plus unfiltered Radio Browser searches. Writes probe-report.md.
// Usage: npm run probe
import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { checkStream } from "./check.js";

const STW = "https://playerservices.streamtheworld.com/api/livestream-redirect/";
const AMP = "https://live.amperwave.net/direct/";
const guesses: Record<string, string[]> = {
  kpwr: ["KPWR", "KPWR_SC", "KPWRFM_SC", "KPWRHD3", "POWER106", "KPWRFMAAC.aac", "KPWRFM_HD"].map(m => STW + m),
  krth: ["audacy-krthfmaac-imc", "audacy-krthfm-imc", "audacy-krthfmmp3-imc"].map(m => AMP + m),
  knx: ["audacy-knxfmaac-imc", "audacy-knxamaac-imc", "audacy-knxfm-imc", "audacy-knxam-imc"].map(m => AMP + m),
  ktwv: ["audacy-ktwvfmaac-imc", "audacy-ktwvfm-imc"].map(m => AMP + m),
  klos: ["KLOSFM", "KLOSFMAAC", "KLOSFM.mp3", "KLOSFMAAC.aac"].map(m => STW + m),
  kkgo: ["KKGOFM", "KKGOFMAAC", "KKGOFM.mp3", "KKGOFMAAC.aac"].map(m => STW + m),
  klax: ["KLAXFM", "KLAXFMAAC", "KLAXFM.mp3", "KLAXFMAAC.aac"].map(m => STW + m),
};
const searches: Record<string, string[]> = {
  kpwr: ["power 106", "power106", "kpwr"],
  klos: ["klos", "95.5"],
  krth: ["k-earth", "kearth", "krth"],
  knx: ["knx"],
  kkgo: ["go country", "kkgo"],
  ktwv: ["the wave", "ktwv", "94.7"],
  klax: ["la raza", "klax", "97.9"],
  extra: ["kost", "myfm", "104.3", "z100", "hot 97.1", "amp radio", "alt 98.7", "kfi", "kabc", "kdld", "wild 94.9"],
};
const HOSTS = ["de1", "de2", "fr1", "nl1", "at1"].map(h => `https://${h}.api.radio-browser.info`);
async function rb(path: string): Promise<any[]> {
  for (const h of HOSTS) {
    try { const r = await fetch(h + path, { headers: { "User-Agent": "SantaMonicaRadio/0.1 (probe)" }, signal: AbortSignal.timeout(15000) }); if (r.ok) return await r.json() as any[]; } catch {}
  }
  return [];
}

let md = "# Probe report\n\n";
md += "## URL guesses\n\n| Station | Result | Content-Type | kbps | URL | Detail |\n|---|---|---|---|---|---|\n";
for (const [id, urls] of Object.entries(guesses)) for (const url of urls) {
  const c = await checkStream(id, url);
  md += `| ${id} | ${c.ok ? "OK" : "FAIL"} | ${c.contentType ?? "-"} | ${c.bitrate ?? "-"} | ${url} | ${c.ok ? "" : c.error} |\n`;
  console.log(id, c.ok ? "OK  " : "FAIL", url);
}
md += "\n## Radio Browser name search (any state/country filter off, US only)\n";
for (const [id, qs] of Object.entries(searches)) {
  md += `\n### ${id}\n\n| Name | State | Check | kbps | Codec | URL |\n|---|---|---|---|---|---|\n`;
  const seen = new Set<string>();
  for (const q of qs) {
    const list = await rb(`/json/stations/search?name=${encodeURIComponent(q)}&countrycode=US&hidebroken=true&limit=15&order=clickcount&reverse=true`);
    for (const x of list) {
      if (seen.has(x.stationuuid)) continue; seen.add(x.stationuuid);
      if (x.state && !/californ|^ca$/i.test(x.state)) continue;
      const url = x.url_resolved || x.url;
      const c = await checkStream(id, url);
      md += `| ${x.name} | ${x.state || "-"} | ${c.ok ? "OK" : "FAIL"} | ${c.bitrate ?? x.bitrate ?? "-"} | ${x.codec} | ${url} |\n`;
      console.log(id, c.ok ? "OK  " : "FAIL", x.name, url);
    }
  }
}
writeFileSync(join(dirname(fileURLToPath(import.meta.url)), "..", "probe-report.md"), md);
console.log("\nDone. Report: catalog/probe-report.md");
