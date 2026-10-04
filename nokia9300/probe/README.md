# Probe 9300

A small MIDlet that measures what an app can rely on, before writing it:

| Menu | What it tests | Logged |
|---|---|---|
| Paměť | Heap: 64 KB `byte[]` blocks, then 256×256 mutable images, until OutOfMemoryError | count, `totalMemory` at the limit |
| Bluetooth GPS | JSR-82: local device, inquiry (paired devices first), SPP service search (UUID 0x1101), then reads NMEA (RMC/GGA, any talker: `$GP`, `$GN`, …) | connect time, first 15 sentences, bytes/s, position, satellites, HDOP |
| Mapové dlaždice | Downloads the tiles covering the screen from a URL template (`{z} {x} {y} {key}`), one request at a time, decodes and draws them. Arrows pan, 1/3 or */# zoom, 5 = GPS position | per tile: HTTP code, bytes, content type, format (PNG/JPEG/WEBP), response/body/decode ms |
| Nastavení | PC address for the log, tile URL template, API key, Bluetooth address (saved in RMS) | – |
| Odeslat log na PC | POST to `http://<PC>/results?name=probe` (`../ota/ota_server.js` saves it in `ota/uploads/`) | – |

Default tile URL: `https://api.mapy.com/v1/maptiles/outdoor/256/{z}/{x}/{y}?apikey={key}` (Mapy.com REST API;
needs a key from developer.mapy.com). Change it in Nastavení if the format differs.

**Android phone as the GPS:** run an app that shares the phone's GPS as NMEA over Bluetooth (Share GPS,
Bluetooth GPS Output, BlueNMEA, …), pair the phones, then *Bluetooth GPS → Hledat zařízení* and pick the phone.

Build: `build.bat` (uses pubtran-j2me's bundled JDK 8, stub jars and ProGuard). Output `bin/probe9300.jar` + `.jad`.
Install: copy the jar next to `../ota/ota_server.js` and download it in the phone's browser, or send it over Bluetooth.
