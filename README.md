# Android app → Nokia 9300 J2ME app: toolkit

Reusable tools and notes for cloning an Android app as a J2ME app for the **Nokia 9300 / 9500
Communicator** (Series 80 v2, Symbian 7.0s, EKA1). Collected while building
[pubtran-j2me](https://github.com/janseris/pubtran-j2me), a client for the Czech public transport
app *Jízdní řády* (project repo:
[Czech-Public-Transport-Symbian-App](https://github.com/janseris/Czech-Public-Transport-Symbian-App)).

## Workflow

| Step | What | Guide |
|---|---|---|
| 1 | Capture the app's API traffic: patch the APK to trust mitmproxy, route the phone over USB | [android/CAPTURE.md](android/CAPTURE.md) |
| 2 | Decode the capture (`flowdump.py`, no mitmproxy install needed; FastRPC decoder) | [android/CAPTURE.md](android/CAPTURE.md#4-decode-the-capture) |
| 3 | Decompile for the meaning of codes, request options, colours and icons | [android/DECOMPILE.md](android/DECOMPILE.md) |
| 4 | Check the backend works with the phone's TLS patch, before writing any J2ME code | [nokia9300/NOTES.md](nokia9300/NOTES.md#check-a-new-backend-first) |
| 5 | Optional: a desktop client to verify the API (pubtran: C# `PubtranClient/`, replays the capture byte for byte) | – |
| 6 | Start the J2ME app from pubtran-j2me | [j2me/STARTER.md](j2me/STARTER.md) |
| 7 | Install, test and debug on the 9300 | [nokia9300/NOTES.md](nokia9300/NOTES.md) |

## Contents

| Path | What |
|---|---|
| `android/scripts/` | PowerShell: `pull_apks.ps1`, `patch_apk.ps1` (apk-mitm), `proxy_on.ps1` / `proxy_off.ps1`; `capture.ps1` / `capture.bat` (mitmweb for chosen hosts) |
| `android/flowdump.py` | List/dump a mitmproxy `.flow`, save bodies, decode FastRPC (`--frpc`) |
| `android/frpc.py` | Seznam FastRPC decoder (Seznam/mapy.cz backends) |
| `android/dex_classes.py` | Decompile chosen classes from `classes.dex` with androguard |
| `android/make_sprite.py` | Build a J2ME icon strip from the app's drawables |
| `nokia9300/NOTES.md` | Device facts, installing, signing (impossible), HTTPS, networking, rules that keep apps from hanging |
| `nokia9300/probe/` | Test MIDlet: heap limit, Bluetooth GPS (NMEA over SPP, e.g. from an Android phone), map tile download/decode; sends its log to the PC |
| `nokia9300/ota/` | HTTP server for installing jars and DLLs from the phone's browser, and receiving logs |
| `nokia9300/ssladaptor/` | The TLS 1.2 patch that works with Java (v20-fix10) and its log build; `ssllog.py` summarises its log |
| `tlsprobe/` | Desktop BearSSL probe that connects the way the phone's TLS patch does |

## Related repositories

- [janseris/pubtran-j2me](https://github.com/janseris/pubtran-j2me): the J2ME app, the starting point for a new one.
- [janseris/symbian-tls](https://github.com/janseris/symbian-tls) (`eka1-java-fixes`) and
  [janseris/bearssl-symbian](https://github.com/janseris/bearssl-symbian) (`eka1-fixes`): the TLS patch sources.
- Build tools for the DLL: `BUILD_SYMBIAN_TLS.md` and `symbian-build/toolchain/` in the project repo.

Captures, patched APKs and decompiled code of other people's apps are for personal and
interoperability use. Don't put them in this repo; keep them in the app's own (private) repo,
and check the service's terms before publishing a client.
