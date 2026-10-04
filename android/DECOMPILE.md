# Decompiling the APK: codes, request options, colours and icons

The capture shows *what* is sent; the app's code says what the numbers mean.

## Code (`classes*.dex`)

- **jadx-gui** ([skylot/jadx](https://github.com/skylot/jadx)): open the APK, browse and search the
  whole app. The most comfortable way.
- **androguard** (`pip install androguard`), scriptable, used for pubtran:
  ```
  unzip base.apk classes.dex
  python dex_classes.py classes.dex --list | findstr /i route
  python dex_classes.py classes.dex Lcz/example/app/model/TransportType;
  ```

What to look for:

- **Enums and constants** that map numbers in responses to meaning (pubtran: `TransportType.find()`
  for vehicle types, `INFO_*` ids for Wi-Fi / air conditioning / delays, `FLAG_*` stop flags).
- **Request builders**: every optional parameter, including the ones your capture didn't use
  (pubtran: via, direct only, transport modes, low-floor, bike, stroller).
- **Paging** and "load more" logic, and how follow-up requests reuse ids from earlier responses.
- Obfuscated apps (R8): names are `a.b.c`, but string constants, JSON keys and API method names
  stay readable; search for them.

## Resources (colours, icons, strings)

```
java -jar apktool.jar d base.apk -o decoded      # res/ as XML + PNG
```

- **Colours:** `decoded/res/values/colors.xml`, dark mode in `values-night/colors.xml`.
  pubtran-j2me's `Theme.java` takes the line colours and on-time/late colours from there.
- **Icons:** `res/drawable-*dpi/` (PNG) and `res/drawable/` (vector XML). Dark-mode variants are in
  `drawable-night*`. Take the highest dpi PNG and scale down:
  ```
  python make_sprite.py icons16.png 16 bus.png tram.png train.png ...
  ```
  Vector drawables must be rendered to PNG first (Android Studio, or pathData → SVG → PNG).
- **Strings:** `res/values-cs/strings.xml` etc. for the app's own wording, so the clone uses the
  same terms.
- Split APKs: density-specific resources may be in `split_config.xhdpi.apk`; decode that too.

Keep the decoded app and the capture in the app's own repo, not in this toolkit.
