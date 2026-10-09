package probe;

import java.io.*;
import java.util.Vector;
import javax.microedition.io.*;
import javax.microedition.lcdui.*;

/**
 * Which map server is fastest from the phone: 6 tiles from each map type Mapy 9300 offers,
 * directly (HttpConnection, as Mapy does without Net Helper) and through Net Helper (when it
 * runs). Mapy.com tiles use the user's own API key, loaded from the PC (mapy_api.key next to
 * ota_server.js) when Probe has none. Times are network only (stopped before decoding).
 * Results go to the PC (uploads/..._tilespeed.txt).
 */
public class TileSpeed extends Canvas implements CommandListener, Runnable {
    static final Command STOP = new Command("Back", Command.BACK, 1);
    static final String HELPER = "http://127.0.0.1:8123/fetch?u=";
    static final int N = 6;

    /** name, URL template ({z} {x} {y} {key}), send our User-Agent? */
    static final String[][] SERVERS = {
        { "OSM https", "https://tile.openstreetmap.org/{z}/{x}/{y}.png", "y" },
        { "OpenTopoMap http", "http://tile.opentopomap.org/{z}/{x}/{y}.png", "y" },
        { "ČÚZK base http", "http://ags.cuzk.gov.cz/arcgis1/rest/services/ZTM_WM/MapServer/tile/{z}/{y}/{x}", "n" },
        { "ČÚZK aerial http", "http://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO_WM/MapServer/tile/{z}/{y}/{x}", "n" },
        { "Mapy.com standard", "https://api.mapy.com/v1/maptiles/basic/256/{z}/{x}/{y}?apikey={key}", "y" },
        { "Mapy.com outdoor", "https://api.mapy.com/v1/maptiles/outdoor/256/{z}/{x}/{y}?apikey={key}", "y" },
        { "Mapy.com aerial", "https://api.mapy.com/v1/maptiles/aerial/256/{z}/{x}/{y}?apikey={key}", "y" },
    };

    final Vector lines = new Vector();
    volatile boolean running = true;
    final StringBuffer report = new StringBuffer();
    final StringBuffer summary = new StringBuffer();
    String ua, key = "";
    boolean helper;
    /**
     * "Like Mapy": the same downloads while the phone does what Mapy does meanwhile: the Bluetooth
     * GPS reading in the background (connect it in "Bluetooth GPS" first, then "Keep reading") and
     * tiles being decoded on another thread. Shows whether those slow the downloads down.
     */
    final boolean likeMapy;
    /**
     * GPS from Net Helper (like Mapy 4.15): Net Helper reads the Bluetooth GPS natively and this
     * test polls its /gps once a second while the tiles download, Java doing no Bluetooth at all.
     */
    final boolean helperGps;
    volatile byte[] lastBody;
    volatile int decodes;
    volatile int polls, pollFails, gpsChanges;
    volatile long pollMax, pollSum;
    volatile String gpsState = "", gpsInfo = "", gpsAge = "";
    /** Requests one at a time, as in Mapy (the GPS poll waits for a tile and the other way round). */
    static final Object NET = new Object();

    TileSpeed() { this(0); }

    /** mode 0: plain, 1: like Mapy with Java reading the GPS, 2: like Mapy 4.15 with Net Helper reading it. */
    TileSpeed(int mode) {
        this.likeMapy = mode != 0;
        this.helperGps = mode == 2;
        setTitle(mode == 2 ? "Map servers + GPS from Net Helper" : likeMapy ? "Map servers: like Mapy" : "Map servers: speed");
        addCommand(STOP);
        setCommandListener(this);
        new Thread(this).start();
    }

    void line(String s) {
        synchronized (lines) {
            lines.addElement(s);
            while (lines.size() > 40) lines.removeElementAt(0);
        }
        report.append(s).append('\n');
        Probe.app.log(s);
        repaint();
    }

    public void run() {
        ua = Probe.app.userAgent;
        line((helperGps ? "Map server speed test with the GPS FROM NET HELPER (+ decoding), " : likeMapy ? "Map server speed test LIKE MAPY (GPS + decoding), " : "Map server speed test, ") + System.getProperty("microedition.platform"));
        if (helperGps) startGpsPoll();
        else if (likeMapy) line(BtGpsScreen.active != null ? "GPS reading in the background (" + BtGpsScreen.bgSentences + " sentences so far)"
                : "GPS NOT reading: connect it in Bluetooth GPS and choose 'Keep reading, back to menu' first");
        if (likeMapy) {
            Thread dec = new Thread() {
                public void run() {
                    while (running) {
                        byte[] b = lastBody;
                        if (b != null) {
                            try { Image.createImage(b, 0, b.length); decodes++; } catch (Throwable e) {}
                        }
                        try { Thread.sleep(100); } catch (InterruptedException e) {}
                    }
                }
            };
            dec.start();
        }
        int gps0 = BtGpsScreen.bgSentences;
        try {
            key = Probe.app.apiKey;
            if (key.length() == 0) {
                String[] r = get("http://" + Probe.app.pc + "/mapy_api.key", false, false, false);
                if (r[1].equals("200")) key = r[4].trim();
                line(key.length() > 0 ? "Mapy.com key loaded from the PC (" + key.length() + " characters)" : "No Mapy.com key (PC: HTTP " + r[1] + "), Mapy.com skipped");
            }
            String[] h = get("http://127.0.0.1:8123/", false, false, false);
            helper = h[1].equals("200");
            line(helper ? "Net Helper running: " + h[4].trim() : "Net Helper not running: direct only (" + h[3] + ")");
            if (helperGps) {
                // give the GPS up to 45 s to connect and send data before the downloads start
                long end = System.currentTimeMillis() + 45000;
                String shown = "";
                while (running && System.currentTimeMillis() < end) {
                    String now = gpsState + ": " + gpsInfo;
                    if (!now.equals(shown)) { shown = now; line("GPS " + now); }
                    if (gpsState.equals("connected") && gpsAge.length() > 0 && !gpsAge.equals("-1") && Integer.parseInt(gpsAge) < 5000) break;
                    BigTest.pause(500);
                }
                line("GPS before the downloads: " + gpsState + ", " + gpsInfo + ", last data " + gpsAge + " ms ago");
            }
            // Brno centre at z16, a different row of tiles per server and way, so no server cache helps
            int x0 = 35800, y0 = 22200;
            for (int s = 0; s < SERVERS.length && running; s++) {
                if (SERVERS[s][1].indexOf("{key}") >= 0 && key.length() == 0) continue;
                if (likeMapy && s != 0 && s != 1 && s != 4) continue;        // OSM, OpenTopoMap, Mapy.com standard
                measure(SERVERS[s][0], SERVERS[s][1], SERVERS[s][2].equals("y"), x0 + 10 * s, y0);
            }
        } catch (Throwable e) {
            line("ERROR: " + e);
        }
        if (helperGps) {
            running = false;
            get("http://127.0.0.1:8123/gps?stop=1", false, false, false);
            line("GPS polls: " + polls + " (failed " + pollFails + "), new positions " + gpsChanges + ", poll " + (polls > 0 ? pollSum / polls : 0)
                + " ms on average, longest " + pollMax + " ms; at the end: " + gpsState + ", " + gpsInfo + ", last data " + gpsAge + " ms ago");
        }
        if (likeMapy) line("Meanwhile: " + decodes + " tile decodes on another thread, " + (BtGpsScreen.bgSentences - gps0) + " GPS sentences read"
            + (BtGpsScreen.active != null ? "" : " (GPS not reading)"));
        line("== Summary (ms per tile without the first one: direct / Net Helper)");
        String sm = summary.toString();
        int st = 0;
        while (st < sm.length()) {
            int e = sm.indexOf('\n', st);
            if (e < 0) e = sm.length();
            line(sm.substring(st, e));
            st = e + 1;
        }
        Probe.app.saveLog();
        try {
            line(Probe.post("http://" + Probe.app.pc + "/results?name=" + (helperGps ? "tilespeed_helpergps" : likeMapy ? "tilespeed_likemapy" : "tilespeed"), report.toString()));
        } catch (Throwable e) {
            line("Sending failed: " + e);
        }
    }

    /** Polls Net Helper's /gps once a second, as Mapy 4.15 does. */
    void startGpsPoll() {
        String a = BtGpsScreen.cleanAddress(Probe.app.btAddress);
        if (a.length() != 12) a = "0C7165CF2E7E";
        final String url = "http://127.0.0.1:8123/gps?addr=" + a;
        line("GPS from Net Helper, address " + a);
        new Thread() {
            public void run() {
                String lastGga = "";
                while (running) {
                    long t0 = System.currentTimeMillis();
                    String[] r = get(url, false, false, false);
                    long ms = System.currentTimeMillis() - t0;
                    if (r[1].equals("200")) {
                        polls++;
                        pollSum += ms;
                        if (ms > pollMax) pollMax = ms;
                        String text = r[4];
                        int st = 0;
                        while (st < text.length()) {
                            int e = text.indexOf('\n', st);
                            if (e < 0) e = text.length();
                            String ln = text.substring(st, e).trim();
                            st = e + 1;
                            if (ln.startsWith("state=")) gpsState = ln.substring(6);
                            else if (ln.startsWith("info=")) gpsInfo = ln.substring(5);
                            else if (ln.startsWith("age=")) gpsAge = ln.substring(4);
                            else if (ln.indexOf("GGA,") == 3 && !ln.equals(lastGga)) { lastGga = ln; gpsChanges++; }
                        }
                    } else pollFails++;
                    long wait = 1000 - (System.currentTimeMillis() - t0);
                    if (wait > 50) BigTest.pause(wait);
                }
            }
        }.start();
    }

    String url(String t, int z, int x, int y) {
        return BigTest.replace(BigTest.replace(BigTest.replace(BigTest.replace(t, "{z}", "" + z), "{x}", "" + x), "{y}", "" + y), "{key}", key);
    }

    void measure(String name, String tmpl, boolean sendUa, int x0, int y) {
        line("== " + name);
        long[] d = series(tmpl, sendUa, x0, y, false, "direct");
        long[] hh = helper ? series(tmpl, sendUa, x0, y + 1, true, "helper") : null;
        summary.append(name).append(": ").append(avg(d)).append(" / ").append(hh == null ? "-" : avg(hh))
            .append(" (first ").append(d[0] < 0 ? "error" : "" + d[0]).append(hh == null ? "" : " / " + (hh[0] < 0 ? "error" : "" + hh[0])).append(")\n");
    }

    /** ms per tile; -1 for an error. */
    long[] series(String tmpl, boolean sendUa, int x0, int y, boolean viaHelper, String how) {
        long[] t = new long[N];
        StringBuffer b = new StringBuffer();
        String sizes = "";
        for (int i = 0; i < N && running; i++) {
            String[] r = get(url(tmpl, 16, x0 + i, y), viaHelper, sendUa, true);
            t[i] = r[1].equals("200") ? Long.parseLong(r[0]) : -1;
            b.append(r[0]).append(r[1].equals("200") ? "" : "(" + r[1] + ")").append(' ');
            if (!r[1].equals("200")) line("  " + how + " " + (i + 1) + ": HTTP " + r[1] + " " + r[3]);
            else if (i == 0) sizes = r[2] + " B " + r[3];
            BigTest.pause(50);
        }
        line(how + ": [" + b.toString().trim() + "] ms" + (sizes.length() > 0 ? ", first " + sizes : ""));
        return t;
    }

    static String avg(long[] t) {
        long sum = 0;
        int n = 0;
        for (int i = 1; i < t.length; i++) if (t[i] >= 0) { sum += t[i]; n++; }
        return n == 0 ? "error" : "" + sum / n;
    }

    static String encode(String s) { return HelperFetch.encode(s); }

    /** { ms (network only), code, bytes, info, body as text when asked } */
    String[] get(String u, boolean viaHelper, boolean sendUa, boolean image) {
        synchronized (NET) { return get0(u, viaHelper, sendUa, image); }
    }

    String[] get0(String u, boolean viaHelper, boolean sendUa, boolean image) {
        HttpConnection c = null;
        InputStream in = null;
        long t0 = System.currentTimeMillis();
        try {
            c = (HttpConnection) Connector.open(viaHelper ? HELPER + encode(u) : u);
            if (sendUa) c.setRequestProperty(viaHelper ? "X-Ua" : "User-Agent", ua);
            int code = c.getResponseCode();
            in = c.openInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            long ms = System.currentTimeMillis() - t0;
            String info = c.getType() == null ? "" : c.getType();
            if (viaHelper) {
                String h = c.getHeaderField("X-Helper");
                if (h != null) info += ", " + h;
                String e = c.getHeaderField("X-Helper-Error");
                if (e != null) info += ", ERROR " + e;
            }
            byte[] body = o.toByteArray();
            if (image && code == 200) lastBody = body;
            if (code != 200 && body.length > 0) info += " " + new String(body, 0, Math.min(body.length, 120));
            return new String[] { "" + ms, "" + code, "" + body.length, info, image ? "" : new String(body) };
        } catch (Throwable e) {
            return new String[] { "" + (System.currentTimeMillis() - t0), "-1", "0", e.toString(), "" };
        } finally {
            try { if (in != null) in.close(); } catch (Throwable e) {}
            try { if (c != null) c.close(); } catch (Throwable e) {}
        }
    }

    public void commandAction(Command c, Displayable d) {
        running = false;
        Probe.app.back();
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setColor(0x1E1F22);
        g.fillRect(0, 0, w, h);
        g.setFont(f);
        int fh = f.getHeight(), rows = h / fh;
        synchronized (lines) {
            int from = Math.max(0, lines.size() - rows);
            for (int i = from; i < lines.size(); i++) {
                String s = (String) lines.elementAt(i);
                g.setColor(s.startsWith("==") ? 0xFCEE74 : 0xDBDEE1);
                g.drawString(s, 3, (i - from) * fh, Graphics.TOP | Graphics.LEFT);
            }
        }
    }
}
