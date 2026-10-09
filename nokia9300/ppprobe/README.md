# PP Probe 9300

Tests the Nokia 9300's second Java, **Personal Profile 1.0** (IBM J9 with AWT), for a map app,
measured the same way as Probe (the MIDP one) so the two compare:

1. the VM, memory (how many decoded 256×256 tiles fit) and screen;
2. plain files (`java.io`): 30 tile-sized files written and read on every drive;
3. the network: Net Helper over a raw socket and over `java.net.URL`, its GPS, a map server directly (http, https);
4. decoding one map tile 5 times (the same tile Probe 3.8 decodes) and drawing it.

Results go to the OTA server (`uploads/..._pptest.txt`) and to `C:\Data\PPProbe\result.txt`.

## Install and run

- Install `ppprobe.sis` from the OTA server (it puts `ppprobe.jar` in `C:\Data\PPProbe\`, two launchers in
  `C:\Moje soubory\` (the File manager's internal memory on a Czech 9300) and makes `C:\logs\j9vm\`, where J9 writes the Java console: errors end up there).
- Start Net Helper first (for the Net Helper and decoding parts).
- Easiest: open `ppprobe.j9` from the OTA server page in the phone's browser (served as `text/j9args`, the phone starts it). Or in the File manager open `PP Probe.j9` in the internal memory. A `.j9` file is the J9 command line
  (`-cp C:\Data\PPProbe\ppprobe.jar ppprobe.PPProbe`). If it doesn't start, try `PP Probe (jcl).j9`
  (the same with `-jcl:ppro10`).
- Another PC address: `-Dpc=host:port` before `-cp` in the `.j9` file (default `192.168.137.1:8000`).

## Build

`./build.sh` (JDK + `makesis`).
