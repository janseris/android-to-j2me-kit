# Capturing an Android app's API traffic

Setup used for *Jízdní řády* (`cz.fhejl.pubtran`): a real phone over USB, mitmproxy on Windows,
and the app patched with apk-mitm so it trusts mitmproxy's certificate.

## 1. Tools

- **mitmproxy** for Windows (`mitmweb`).
- **adb**: Android SDK platform-tools (Visual Studio installs it in
  `C:\Program Files (x86)\Android\android-sdk\platform-tools`). Add it to the user PATH:
  ```powershell
  $sdk = "C:\Program Files (x86)\Android\android-sdk"
  $p = [Environment]::GetEnvironmentVariable("Path","User")
  [Environment]::SetEnvironmentVariable("Path", "$p;$sdk\platform-tools", "User")
  ```
  Only terminals opened afterwards see it (restart Windows Terminal completely).
- **Node.js** (`npx apk-mitm`) and **Java 11+** (apktool).
- The newest **apktool** jar from [iBotPeaches/Apktool releases](https://github.com/iBotPeaches/Apktool/releases),
  saved as `apktool.jar`. apk-mitm's bundled apktool (2.9.3) failed on an app targeting SDK 36
  (`attribute android:defaultLocale not found`).
- A real Android phone with USB debugging.

**Why not the emulator:** Visual Studio's emulator image was a *Google Play* image, which can't be
rooted (`adbd cannot run as root in production builds`), so mitmproxy's CA can't become a system
certificate. A *Google APIs* image (API ≤ 33, `-writable-system`) can, but has no Play Store to
install the app from.

## 2. Patch the app

Since Android 7 apps trust only *system* CAs unless they opt in. apk-mitm makes the app opt in and
removes certificate pinning.

```powershell
cd <work folder>
.\pull_apks.ps1 cz.example.app        # base + split APKs -> app.apks; warns about Flutter
.\patch_apk.ps1 cz.example.app        # apk-mitm with apktool.jar, uninstall original, install patched
```

- The original has a different signature, so it is uninstalled first and **its data is lost**.
- **Flutter apps** (`libflutter.so` in an APK) ignore the system proxy and don't use the Android
  CA store; this method doesn't work for them.

## 3. Route the phone through mitmproxy

```powershell
.\proxy_on.ps1                                  # adb reverse 8080 + system proxy 127.0.0.1:8080
capture.bat "api\.example\.com" myapp           # mitmweb, only this host; saves myapp.flow + myapp.har
```

Once per phone, install mitmproxy's CA: open `http://mitm.it` in the phone's browser → Android →
download, then Settings → search "CA certificate" → install.

Intercept only the app's backend (`--allow-hosts`): other apps keep working and don't flood the log
with TLS errors. Write down the user flow you record (which screens, what you typed), so you can
match requests to actions later.

**Turn the proxy off afterwards** (`.\proxy_off.ps1`), or the phone has no internet whenever
mitmproxy isn't running.

### Capturing a login

Apps often log in through a web page in the browser (Chrome / Custom Tab) and get a code back
through a redirect to the app. To see the whole flow:

- Add the login hosts to `--allow-hosts` (Seznam: `seznam\.cz`). Chrome on Android trusts
  user-installed CAs, so the login page is captured too (Firefox doesn't by default).
- **Capture the login into its own file** (`capture.bat "..." myapp_login`). It contains your
  password in plain text and the session tokens. Keep it private, never commit it, and log out
  in the app afterwards (and change the password if the file was shared by mistake).
- `flowdump.py` masks passwords, tokens, codes and cookies in its output unless `--show-secrets`.

## 4. Decode the capture

Use the `.flow` file; the `.har` mangles binary bodies.

```
python flowdump.py myapp.flow            # one line per request: method, URL, status, sizes
python flowdump.py myapp.flow -v         # + headers and text bodies (JSON etc.)
python flowdump.py myapp.flow --out raw  # every body as NNN_req.bin / NNN_resp.bin
python flowdump.py myapp.flow --frpc     # Seznam FastRPC bodies (magic CA 11) as JSON
```

`flowdump.py` reads mitmproxy's format itself; mitmproxy doesn't have to be installed in Python.

Things to look for:

- Authentication: API keys, tokens, request signatures (pubtran had none).
- The body format: JSON, protobuf, FastRPC, … A binary format needs an encoder/decoder in J2ME too.
- Response sizes: the 9300 has little heap; prefer requests that return less (paging, counts).
- Compression: MIDP's `HttpConnection` doesn't decompress anything by itself. Check that the server
  answers uncompressed when the request has no `Accept-Encoding` (pubtran-backend does), or the
  app needs its own inflater.
- Hosts: every host the J2ME app needs must work with the phone's TLS patch (see nokia9300/NOTES.md).
