# Starting a new J2ME app from pubtran-j2me

[pubtran-j2me](https://github.com/janseris/pubtran-j2me) (tag `v1.2`) is the working base: it builds
with everything bundled (JDK 8, ProGuard, stub jars, KEmulator; needs only Node.js), and its
networking is tuned for the 9300's TLS patch. It is itself a fork of
[gtrxac/discord-j2me](https://github.com/gtrxac/discord-j2me); keep that attribution and licence.

## Create the repo

```
git clone https://github.com/janseris/pubtran-j2me.git myapp-j2me
cd myapp-j2me
git remote rename origin pubtran          # to pull fixes later
# create janseris/myapp-j2me on GitHub, then:
git remote add origin https://github.com/janseris/myapp-j2me.git
git push -u origin main
```

## Rename

- `manifest.mf`: `MIDlet-Name`, `MIDlet-Vendor`, the display name in every `MIDlet-1` line (one
  per `//#ifdef` branch), icons, `MIDlet-Version: 1.0`. A new name/vendor makes it a separate app
  on the phone. The main class stays `com.gtrxac.discord.App` (ProGuard renames it to `a`).
- `build.json`: target names = output jar names (`pubtran_s80` → `myapp_s80`).
- Package `src/pubtran/` → `src/myapp/` (optional, but clearer).

## What to keep (generic)

| Class | What it does |
|---|---|
| `RequestThread`, `RequestCallback` | Background request with cancel ("Zrušit"), stall handling, error alert |
| `PubtranApi` (`call()`, `acquire()`, `Attempt`) | One request at a time, attempt thread, 15 s stall timeout, 3 attempts. Rename; replace the endpoint methods |
| `NativeHttp`, `fi/gtrxac/bluewap/http/*` | `HttpConnection` wrapper with phase timings |
| `RequestLog`, `LogEntry`, `LogScreen`, `LogDetailScreen`, `PcUpload` | Request log in RMS, send to PC |
| `TlsTestScreen`, `TlsInfo` | HTTPS test screen: URL batches, POST tests, results to the PC. Point it at the new backend |
| `CardCanvas` | Dark scrolling cards with focus, rows, spinner-only repaint while loading |
| `Theme` | Colours, fonts, icon strip, badges, delay pills, text wrap. Replace the colours/icons with the new app's |
| `TileScreen`, `StartScreen`, `LoadingHost`, `LoadingScreen`, `Spinner` | Start screen tiles and loading overlay (see rule 1 in NOTES.md) |
| `AppSettings` | Settings in RMS |
| `Fmt` | CLDC helpers (split, time/duration formatting); partly app-specific |
| `Frpc*` | FastRPC encoder/decoder: only for Seznam backends |
| `sdk/compile_all.js` | Build, with the `MIDlet-Version` bump |

App-specific (replace): `Place`, `Route*`, `Trip*`, `Info`, `SearchState`, `RecentPlaces`,
`PlaceScreen`, `ResultsScreen`, `RouteScreen`, `TripScreen`, `WhenScreen`.

The Java TLS classes (`JavaTls*`, `SniServerName`, `org/bouncycastle`) are behind `//#ifdef JAVA_TLS`
for emulators and other phones; on the 9300 they're unused.

## First steps

1. Build and install the renamed app unchanged; check the HTTPS test screen against the new backend.
2. Write the API layer (request encoder + response parser) and test it in KEmulator.
3. Screens on `CardCanvas`.
4. Test on the phone with the request log and, if needed, `ssladaptor_log.dll`.
