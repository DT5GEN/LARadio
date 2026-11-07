# Own playlists (imports)

Drop `.m3u` / `.m3u8` playlists here, one folder per region: `imports/es`, `imports/se`, `imports/la`.
`npm run import` (and the daily GitHub Actions run) reads them, adds the stations to that region and
`npm run build` checks every stream, dropping dead ones. Format:

```
#EXTM3U
#EXTINF:-1,Station Name
https://example.com/stream.mp3
```

Where to get such files: radio-browser.info (country page -> "Download as M3U"), or any internet-radio list on GitHub.
Station names already in the curated lists are skipped.
