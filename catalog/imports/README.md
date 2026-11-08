# Own playlists (imports)

Drop playlists here (`.m3u`, `.m3u8`, `.pls`, or a Radio Browser JSON export saved as `.json`/`.txt`), one folder per region: `imports/es`, `imports/se`, `imports/la`.
`npm run import` (and the daily GitHub Actions run) reads them, adds the stations to that region and
`npm run build` checks every stream, dropping dead ones. Format:

```
#EXTM3U
#EXTINF:-1,Station Name
https://example.com/stream.mp3
```

Where to get such files: radio-browser.info (country page -> "Download as M3U"), or any internet-radio list on GitHub.
Station names already in the curated lists are skipped.

Radio Browser JSON: open `https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/SE?hidebroken=true&order=clickcount&reverse=true&limit=300`
in the browser (country code ES / SE / US), save the page as `.json` into the region folder.
Links to `.m3u`/`.pls` inside playlists are resolved to the real stream address during `npm run import`.
