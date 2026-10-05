# Nokia 9300 / 9500 (Series 80 v2) notes for J2ME apps

- **Platform:** Series 80 v2 on **Symbian 7.0s, EKA1**. Not S60: S60v3+ (Symbian 9, EKA2) patches
  and tricks don't apply. Screen 640×200 (inner display), ARM9 at ~150 MHz, slow.
- **Java:** MIDP 2.0, CLDC 1.1 (so `double` works; no `Math.log`/`atan2`, write your own).
  Compile against `cldcapi11.jar`.
- The application manager shows only *major.minor* of `MIDlet-Version`.

### Java APIs on the phone

From the user guide (*Nokia 9300 User Guide*, "Java MIDP", p. 79) and the
*JavaSpecs* test MIDlet run on our 9300 (firmware 05.22):

| API | Supported |
|---|---|
| CLDC 1.1, MIDP 2.0, JTWI 1.0 (JSR-185) | yes |
| **Bluetooth, JSR-82** (`javax.bluetooth`; SPP via `btspp://`) | **yes**; OBEX (`javax.obex`) no |
| File and PIM, JSR-75 | yes (user guide: "Java File", "Java PIM") |
| WMA (JSR-120), Mobile Media (JSR-135, also video), Nokia UI | yes |
| **Location, JSR-179** | **no**, and no GPS: position only from an external Bluetooth GPS (NMEA) |
| Web services (172), security (177), SIP (180), 3D (184), WMA 2.0 (205) | no |
| Pointer events | no (keyboard only; key repeat events yes) |

Other measured values (JavaSpecs / JBenchmark):

- Canvas size **523×168** (full screen is 640×200, the rest is the command button area), 65536 colours, double buffered.
- `Runtime.totalMemory()` reported 409–640 KB; the heap grows on demand, so measure the real
  limit with `probe/` (*Paměť*) before planning caches.
- **Measured with `probe/` (2026-10-04):** the app got **16 MB** of heap (`byte[]` 16192 KB,
  `totalMemory` 16777216) and 44 mutable 256×256 images before OutOfMemoryError: plenty for map tiles.
- **Map tiles (OSM, measured with `probe/` 1.6 over USB, 2026-10-04):** 256×256 PNG, 30–42 KB each;
  per tile ~0.4–0.9 s until the response (new HTTPS connection each time), ~0.3 s body, **~0.5 s PNG
  decode**. One screen (523×168) needs 6 tiles: ~9 s with a 0.3 s pause between tiles. 24 decoded tiles
  in memory worked (images live outside the Java heap counter; `totalMemory` stayed at 640 KB).
  The 9300 sends our own `User-Agent` unchanged. The first request after a while sometimes fails with
  `SymbianOS error -5120` (DNS) or stalls: retrying on a new connection fixed it.
- Timer resolution ~62 ms. JBenchmark 1515; JBenchmark 3D crashes (no JSR-184).
- Bluetooth 1.1 with the Serial Port Profile (user guide, "Bluetooth connectivity").
- **Bluetooth GPS works (probe 2.1, 2026-10-05, on a bus):** Android phone with *GPS NMEA Tether*
  (com.stephentaylor.gpsnmeatether) over SPP, 3.5 minutes without a break, 1 position per second,
  largest gap 1.3 s, delay constant (GPS time vs receive time stayed within -470..+140 ms): real time.
  Two things were needed:
  - **Send only GGA + RMC at 1 Hz** (switch off VTG, GSA, GSV; strict NMEA on, keepalive heartbeat
    off): ~150 B/s. With all sentences (multi-GNSS GSA/GSV, ~1-2 KB/s) the 9300's Java Bluetooth
    crashed after a few lines: `E32USER-CBase 40` with `read(byte[512])`, `KERN-EXEC 3` in
    `jes-...-java-comms` with single-byte `read()`.
  - **Read exactly what `available()` reports** (`read(buf, 0, available)`), sleeping 50 ms when nothing
    is waiting.
  Also: don't update Form items from the Bluetooth thread (KERN-EXEC 3 in "main"); draw on a Canvas.
- `DiscoveryAgent.retrieveDevices(PREKNOWN)` returned no devices although the phones were paired;
  connecting by address works.
- **While the 9300 has a Bluetooth connection to the PC (PC Suite), it can't search for devices**
  (the search ends at once with nothing found) **and other devices can't find it.** Disconnect the PC
  first (observed 2026-10-04). So a Bluetooth GPS and a PC Suite Bluetooth connection don't mix:
  install apps over USB or the browser (`ota/`) when testing Bluetooth GPS.
- Unsigned MIDlets should be allowed Bluetooth and files after a permission prompt (MIDP 2.0 untrusted domain); not confirmed on the phone yet (`probe/`).

## Signing is impossible, so: no sockets, no Java TLS

Unsigned MIDlets get a SecurityException for `socket://`, so a Java TLS stack (BouncyCastle) can't
be used. Signed suites are always refused on the 9300 ("Instalace aplikace byla odmítnuta serverem
jazyka Java" / "Digitální podpis nelze ověřit"), with any certificate, even imported and marked
trusted: the set of root certificates that can verify a MIDlet is closed (in ROM).

- Forum Nokia, *MIDP 2.0: Tutorial On Signed MIDlets*: self-signed certificates work only in the
  emulator, "the set of root certificates is closed".
- discord-j2me's "Darkman" trick and nnproject's [Java permissions patch](http://nnproject.cc/jrtsecuritypatch)
  are for Symbian 9.x only.
- [gtrxac.fi/j2me/proxyless](https://gtrxac.fi/j2me/proxyless): Series 80 2nd Edition is supported
  only through the system TLS 1.2 patch.

Full record of the tests: pubtran-j2me README, *Java TLS / signing tests*.

**Consequence:** networking is `HttpConnection` only (`http://` and `https://`). HTTPS goes through
the phone's `SSLADAPTOR.dll`, which has to be replaced by the patch below. No signing keys needed.

## HTTPS: the TLS 1.2 patch

The original `SSLADAPTOR.dll` can't talk to modern servers. Install `ssladaptor/ssladaptor.dll`
(**v20-fix10**, from [janseris/symbian-tls](https://github.com/janseris/symbian-tls) tag `v20-fix10`)
on the phone as `C:\System\Libs\ssladaptor.dll` and restart the phone.

- `ssladaptor_log.dll`: the same with a log, one line per connection, to `C:\Logs\SSL\SSLLog.txt`
  when that folder exists. Install it as `ssladaptor.dll` for diagnosis only.
  `python ssllog.py SSLLog.txt` turns it into a table (handshake time, resumed or not, bytes).
- What it fixes for Java (SNI from the `Host:` header, reads, two-part POSTs, hangs after failed
  handshakes, session resumption, 16 KB read-ahead): pubtran-j2me README, *Working configuration*.
- The certificate chain is **not verified** (as in the original EKA1 build of the patch).
- Typical numbers: full handshake ~1 s, resumed ~0.25 s (servers that resume by session id), HTTPS
  100–140 KB/s over USB, the same as plain HTTP. Each `HttpConnection` is a new TCP + TLS connection.
- MIDP reports the TLS version/cipher wrongly (SSL 3.0, 0x0000); the connection is really TLS 1.2.

### Check a new backend first

Before porting, test the backend's hosts with the phone's TLS stack:

- **On the PC:** `tlsprobe/` connects like the patch (BearSSL client, no verification, optional
  SNI): `tlsprobe [-sni|-nosni] host[/path] ...` (without a flag it tries both). It shows whether the handshake works with the
  ciphers/curves the patch offers.
- **On the phone:** pubtran-j2me's HTTPS test screen loads any list of URLs (GET and POST) and
  sends results to the PC; copy it into the new app early.

## Phone's internet over USB

The phone gets internet over the USB cable from the PC; the PC is `192.168.137.1` (Windows
Internet Connection Sharing's address). The phone's browser and Java use it like any access point.
<!-- TODO: exact steps (PC Suite connection / sharing settings, access point on the phone). -->

## Installing apps

- **Every build needs a new `MIDlet-Version`.** A jar with the same name, vendor and version as a
  suite the phone knows, but different content, is refused as "Neplatný archív aplikace", even
  after uninstalling. pubtran-j2me's `sdk/compile_all.js` raises *major.minor* on every build.
- **Keep the `.jad` and `.jar` of one build together.** PC Suite's Application Installer and the
  browser use the `.jad` when present; an old `.jad` gives "Neplatný archív aplikace" too.
- Ways to install:
  - **Bluetooth:** send the `.jar` alone. The most reliable.
  - **Browser:** run `ota/start_ota_server.bat` (port 8000), open `http://192.168.137.1:8000/` and
    download the **`.jar`** (opening a `.jad` link failed). It serves the files in its own folder:
    copy the jar (and a DLL, if needed) next to `ota_server.js`.
  - **Nokia PC Suite 6.6** Application Installer: press refresh before every install.
- Logs to the PC: `ota_server.js` accepts `POST /results?name=x` (saved to `ota/uploads/`), and
  `/upload` is a browser form for files such as `SSLLog.txt`.

## Rules that keep apps from hanging

Learned the hard way (pubtran-j2me `LESSONS_NOKIA_9300.md`):

1. **No full-screen repaints in a loop while a request runs.** The TLS patch runs in the Java
   process (thread `jes-dd-java-comms`); a 640×200 repaint every 120 ms starved it and requests
   stalled. Repaint only the spinner area.
2. **Never close an `HttpConnection` (or its streams) from another thread** while a request runs:
   KERN-EXEC 3 in `jes-dd-java-comms`, then a frozen phone. To cancel, abandon the request and
   ignore its late result.
3. **Every request needs a way out:** own thread per attempt, abandon after ~15 s with no progress,
   retry on a new connection (up to 3×), a "Zrušit" command.
4. **One request at a time.**
5. **Few requests:** no keep-alive between `HttpConnection`s; each costs a handshake.
6. **Keep a request log in RMS** with phase timings and a "send to PC" command; it survives freezes.
7. **Logging is slow** (~15 ms per line on the phone): it changes timing and can hide bugs.

## Forms and menus (found with Mapy 9300)

- **ChoiceGroups in a Form take the Up/Down keys**: a POPUP changes its value with Up/Down, an
  EXCLUSIVE (radio) group moves inside itself; either way the other fields are hard to reach.
  Settings work better as a `List` of "name: value" rows: Up/Down move between rows, Enter opens a
  `List` of the values (or a `TextBox` for text), nothing changes until "Uložit" (Mapy 9300's
  `SettingsScreen`).
- **Akce menu (Menu key) over a Canvas**: the arrow keys used in the menu are also delivered to the
  Canvas (`keyPressed`), `hideNotify()` is not called and `isShown()` stays true, so the Canvas can't
  tell. A Canvas that uses the arrows (map cursor) should have **no Commands** and draw its own menu,
  opened by a key it receives (Tab; unknown key codes are logged).
- **Side buttons without Commands**: on a Canvas with no Commands the four side (CBA) buttons
  arrive in `keyPressed` as key codes MIDP doesn't name (negative, not arrows/Enter). Mapy 9300
  learns them once ("press the top side button"... the bottom one, saved in RMS) and maps them to
  its own actions; it draws the button labels itself in a bar on the right. Works on the phone.
- The Enter that picks an Akce menu item can reach the Canvas as well: act on Enter only on release,
  and not when a command arrived meanwhile.
