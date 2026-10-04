#!/usr/bin/env python3
"""
Print decompiled Java source of chosen classes from a classes.dex (androguard, no jadx needed).

  pip install androguard
  python dex_classes.py classes.dex Lcz/example/app/model/TransportType;     # one class
  python dex_classes.py classes.dex Lcz/example/app/                          # a whole package (prefix)
  python dex_classes.py classes.dex --list | grep -i route                    # find class names

Class names use the dex form: L<package with slashes>/<Name>;
For browsing a whole app, jadx-gui (https://github.com/skylot/jadx) is more comfortable.
"""
import sys
from androguard.misc import AnalyzeDex
try:
    from loguru import logger; logger.remove()
except Exception:
    pass
from androguard.decompiler.decompile import DvClass

if len(sys.argv) < 3:
    sys.exit(__doc__)
h, d, dx = AnalyzeDex(sys.argv[1])
want = sys.argv[2:]
for c in d.get_classes():
    name = c.get_name()
    if want == ['--list']:
        print(name); continue
    if any(name == w or name.startswith(w) for w in want):
        try:
            dc = DvClass(c, dx); dc.process(); print(dc.get_source())
        except Exception as e:
            print('// ERROR', name, e)
