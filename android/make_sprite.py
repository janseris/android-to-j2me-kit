#!/usr/bin/env python3
"""
Build a horizontal icon strip (sprite) for J2ME from PNG files, e.g. the app's drawables
decoded by apktool (res/drawable-*-night/ for dark mode).

  pip install pillow
  python make_sprite.py out.png 16 bus.png tram.png train.png ...
  python make_sprite.py out.png 16 --bg 383A40 bus.png ...   # flatten onto a background colour

Icon i is at x = i * size. Draw it in J2ME by clipping to (x, y, size, size) and drawing the
strip at x - i * size (see Theme.drawIcon in pubtran-j2me). One PNG instead of many saves jar
space and file entries.

Vector drawables (XML) have to be rendered to PNG first: open the APK in Android Studio
(Vector Asset > export) or convert the pathData to SVG and render it (e.g. with cairosvg).
--bg: some old phones draw PNG alpha badly; flattening onto the card colour avoids grey fringes.
"""
import sys
from PIL import Image

args = sys.argv[1:]
if len(args) < 3:
    sys.exit(__doc__)
out, size, rest = args[0], int(args[1]), args[2:]
bg = None
if rest[0] == '--bg':
    bg = tuple(int(rest[1][i:i + 2], 16) for i in (0, 2, 4)); rest = rest[2:]
strip = Image.new('RGBA', (size * len(rest), size), (0, 0, 0, 0) if bg is None else bg + (255,))
for i, path in enumerate(rest):
    im = Image.open(path).convert('RGBA')
    im.thumbnail((size, size), Image.LANCZOS)
    x = i * size + (size - im.width) // 2
    y = (size - im.height) // 2
    strip.alpha_composite(im, (x, y))
if bg is not None:
    strip = strip.convert('RGB')
strip.save(out, optimize=True)
print('%s: %d icons, %dx%d' % (out, len(rest), strip.width, strip.height))
