package probe;

import java.io.*;
import java.util.Hashtable;
import javax.microedition.io.*;
import javax.microedition.lcdui.*;

/**
 * Map tiles, loaded the way pubtran-j2me loads its requests on the 9300:
 * - one request at a time, each attempt on its own thread; an attempt with no progress for
 *   STALL_MS is abandoned (never closed from another thread), up to ATTEMPTS tries;
 * - a short pause between tiles so the phone keeps answering the PC's keep-alive pings on USB;
 * - live progress (phase, bytes) in the status strip, repainting only that strip or the new tile;
 * - tiles are kept in memory, so panning downloads only the new ones.
 * Keys: arrows pan by half a tile, 1/3 (or star/hash) zoom, 5 = GPS position.
 */
class TileScreen extends Canvas implements CommandListener, Runnable {
    static final Command RELOAD = new Command("Načíst znovu", Command.SCREEN, 1);
    static final Command GPS = new Command("Na polohu GPS", Command.SCREEN, 2);
    static final int T = 256;
    static final int STALL_MS = 15000, ATTEMPTS = 3, PAUSE_MS = 300, CACHE_MAX = 24;

    final Probe p = Probe.app;
    int zoom = 13;
    double cx, cy;                       // centre in world pixels at zoom
    final Hashtable cache = new Hashtable();   // "z/x/y" -> Image
    final java.util.Vector order = new java.util.Vector();
    volatile boolean loading, again;
    volatile String status = "", phase = "";
    volatile int phaseBytes;
    volatile long lastActivity;
    int bytesTotal, loaded, errors;
    long msTotal;
    String lastError = "";

    TileScreen() {
        addCommand(RELOAD); addCommand(GPS); addCommand(Probe.BACK);
        setCommandListener(this);
        double lat = 50.0875, lon = 14.4213;    // Praha, Staroměstské náměstí
        if (Nmea.lat != 0) { lat = Nmea.lat; lon = Nmea.lon; }
        center(lat, lon);
        load();
    }

    void center(double lat, double lon) {
        double n = (double) (T << zoom);
        cx = (lon + 180.0) / 360.0 * n;
        double r = Math.toRadians(lat);
        cy = (1.0 - MathX.ln(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * n;
    }

    public void commandAction(Command c, Displayable d) {
        if (c == Probe.BACK) { again = false; p.back(); }
        else if (c == RELOAD) { synchronized (cache) { cache.clear(); order.removeAllElements(); } load(); }
        else if (c == GPS && Nmea.lat != 0) { center(Nmea.lat, Nmea.lon); load(); }
    }

    protected void keyPressed(int key) {
        int a = 0;
        try { a = getGameAction(key); } catch (Throwable e) {}
        if (a == LEFT) cx -= T / 2; else if (a == RIGHT) cx += T / 2;
        else if (a == UP) cy -= T / 2; else if (a == DOWN) cy += T / 2;
        else if ((key == '3' || key == '*') && zoom < 18) { zoom++; cx *= 2; cy *= 2; }
        else if ((key == '1' || key == '#') && zoom > 3) { zoom--; cx /= 2; cy /= 2; }
        else if (key == '5' && Nmea.lat != 0) center(Nmea.lat, Nmea.lon);
        else return;
        repaint();
        load();
    }

    /** Starts the loader, or tells the running one to look again when it's done. */
    void load() {
        synchronized (this) {
            if (loading) { again = true; return; }
            loading = true;
        }
        new Thread(this).start();
    }

    public void run() {
        try {
            do {
                again = false;
                loadVisible();
            } while (again);
        } finally {
            loading = false;
            repaintStatus();
        }
    }

    void loadVisible() {
        int w = getWidth(), h = getHeight();
        int z = zoom;
        double ccx = cx, ccy = cy;
        int x0 = (int) Math.floor((ccx - w / 2) / T), x1 = (int) Math.floor((ccx + w / 2) / T);
        int y0 = (int) Math.floor((ccy - h / 2) / T), y1 = (int) Math.floor((ccy + h / 2) / T);
        int max = 1 << z;
        // count the missing ones first, for the progress text
        int missing = 0;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
                if (y >= 0 && y < max && !cache.containsKey(key(z, x & (max - 1), y))) missing++;
        p.log("--- tiles z" + z + " x " + x0 + ".." + x1 + " y " + y0 + ".." + y1 + ", missing " + missing + ", screen " + w + "x" + h);
        bytesTotal = 0; msTotal = 0; loaded = 0; errors = 0; lastError = "";
        int k = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (again && (z != zoom || ccx != cx || ccy != cy)) return;   // view changed: start over
                if (y < 0 || y >= max) continue;
                int wx = x & (max - 1);
                String key = key(z, wx, y);
                if (cache.containsKey(key)) continue;
                k++;
                status = "dlaždice " + k + "/" + missing;
                Image im = fetch(z, wx, y);
                if (im != null) {
                    put(key, im);
                    loaded++;
                    repaintTile(x, y);     // only the new tile
                }
                repaintStatus();
                // let the phone answer the PC's USB keep-alive between downloads
                try { Thread.sleep(PAUSE_MS); } catch (InterruptedException e) {}
            }
        }
        status = (missing == 0 ? "vše z paměti" : loaded + "/" + missing + " dlaždic, " + (bytesTotal / 1024) + " KB, " + msTotal + " ms")
            + (errors > 0 ? ", CHYBY " + errors + ": " + lastError : "");
        p.memLine("after tiles (cache " + cache.size() + ")");
    }

    static String key(int z, int x, int y) { return z + "/" + x + "/" + y; }

    void put(String key, Image im) {
        synchronized (cache) {
            cache.put(key, im);
            order.addElement(key);
            while (order.size() > CACHE_MAX) {
                cache.remove(order.elementAt(0));
                order.removeElementAt(0);
            }
        }
    }

    /** One tile: attempts on their own threads, abandoned (not closed) after STALL_MS without progress. */
    Image fetch(int z, int x, int y) {
        String url = replace(replace(replace(replace(p.tileUrl, "{z}", "" + z), "{x}", "" + x), "{y}", "" + y), "{key}", p.apiKey);
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            Attempt a = new Attempt(url, z + "/" + x + "/" + y);
            lastActivity = System.currentTimeMillis();
            a.start();
            while (!a.done) {
                try { Thread.sleep(250); } catch (InterruptedException e) {}
                repaintStatus();
                if (System.currentTimeMillis() - lastActivity > STALL_MS) {
                    p.log("tile " + a.name + ": no progress for " + (STALL_MS / 1000) + " s in '" + phase + "', abandoned (attempt " + attempt + ")");
                    a.abandoned = true;
                    break;
                }
            }
            if (a.done && a.image != null) return a.image;
            if (a.done && !a.retry) return null;      // HTTP error or bad image: no point retrying
        }
        errors++;
        lastError = "bez odezvy";
        return null;
    }

    class Attempt extends Thread {
        final String url, name;
        volatile boolean done, abandoned, retry;
        Image image;

        Attempt(String url, String name) { this.url = url; this.name = name; }

        void phase(String s, int bytes) {
            if (abandoned) return;
            phase = s; phaseBytes = bytes;
            lastActivity = System.currentTimeMillis();
        }

        public void run() {
            long t0 = System.currentTimeMillis();
            HttpConnection c = null;
            InputStream in = null;
            try {
                phase("připojování", 0);
                c = (HttpConnection) Connector.open(url);
                if (p.userAgent.length() > 0) c.setRequestProperty("User-Agent", p.userAgent);
                phase("TLS + odpověď serveru", 0);
                int code = c.getResponseCode();
                long tResp = System.currentTimeMillis();
                String type = c.getType();
                int len = (int) c.getLength();
                in = c.openInputStream();
                byte[] b = readAll(in, len);
                long tBody = System.currentTimeMillis();
                if (abandoned) return;
                bytesTotal += b.length;
                msTotal += tBody - t0;
                String magic = magic(b);
                if (code != 200) {
                    errors++;
                    lastError = "HTTP " + code + " " + type;
                    p.log("tile " + name + ": HTTP " + code + " " + type + " " + b.length + " B: "
                        + new String(b, 0, Math.min(b.length, 200)));
                    return;
                }
                phase("dekódování", b.length);
                long td0 = System.currentTimeMillis();
                try {
                    image = Image.createImage(b, 0, b.length);
                } catch (Throwable e) {
                    errors++;
                    lastError = "dekódování " + magic + " " + b.length + " B: " + e;
                    p.log("tile " + name + ": decode failed, " + b.length + " B " + type + " (" + magic + "): " + e + ", start: " + hex(b, 16));
                    return;
                }
                p.log("tile " + name + ": " + b.length + " B " + type + " (" + magic + "), response " + (tResp - t0)
                    + " ms, body " + (tBody - tResp) + " ms, decode " + (System.currentTimeMillis() - td0) + " ms");
            } catch (Throwable e) {
                retry = true;
                lastError = e.toString();
                p.log("tile " + name + " failed after " + (System.currentTimeMillis() - t0) + " ms in '" + phase + "': " + e);
            } finally {
                // closed only here, in the thread that opened it
                try { if (in != null) in.close(); } catch (Throwable e) {}
                try { if (c != null) c.close(); } catch (Throwable e) {}
                done = true;
            }
        }

        byte[] readAll(InputStream in, int len) throws IOException {
            ByteArrayOutputStream o = new ByteArrayOutputStream(len > 0 ? len : 16384);
            byte[] buf = new byte[2048];
            int r, n = 0;
            phase("stahování", 0);
            while ((r = in.read(buf)) > 0) {
                o.write(buf, 0, r);
                n += r;
                phase("stahování", n);
                if (len > 0 && n >= len) break;
            }
            return o.toByteArray();
        }
    }

    static byte[] readAll(InputStream in, int len) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(len > 0 ? len : 2048);
        byte[] buf = new byte[2048];
        int r;
        while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        return o.toByteArray();
    }

    static String hex(byte[] b, int n) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < Math.min(n, b.length); i++) {
            int v = b[i] & 0xff;
            sb.append("0123456789abcdef".charAt(v >> 4)).append("0123456789abcdef".charAt(v & 15)).append(' ');
        }
        return sb.toString();
    }

    static String magic(byte[] b) {
        if (b.length > 4 && (b[0] & 0xff) == 0x89 && b[1] == 'P') return "PNG";
        if (b.length > 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8) return "JPEG";
        if (b.length > 12 && b[0] == 'R' && b[8] == 'W' && b[9] == 'E') return "WEBP";
        if (b.length > 3 && b[0] == 'G' && b[1] == 'I') return "GIF";
        return "?";
    }

    static String replace(String s, String a, String b) {
        int i = s.indexOf(a);
        return i < 0 ? s : s.substring(0, i) + b + s.substring(i + a.length());
    }

    // ---- painting: only the status strip during loading, or the tile that just arrived ----

    int stripHeight() {
        return 2 * Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL).getHeight() + 3;
    }

    void repaintStatus() {
        int h = getHeight();
        repaint(0, h - stripHeight(), getWidth(), stripHeight());
    }

    void repaintTile(int x, int y) {
        int w = getWidth(), h = getHeight();
        int px = x * T - ((int) cx - w / 2), py = y * T - ((int) cy - h / 2);
        repaint(px, py, T, T);
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        int ox = (int) cx - w / 2, oy = (int) cy - h / 2;
        int x0 = (int) Math.floor((double) ox / T), x1 = (int) Math.floor((double) (ox + w) / T);
        int y0 = (int) Math.floor((double) oy / T), y1 = (int) Math.floor((double) (oy + h) / T);
        int max = 1 << zoom;
        int clipY = g.getClipY(), clipH = g.getClipHeight();
        boolean onlyStrip = clipY >= h - stripHeight() && clipY + clipH <= h;
        if (!onlyStrip) {
            g.setColor(0x2B2D31);
            g.fillRect(0, 0, w, h);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int px = x * T - ox, py = y * T - oy;
                    Image im = (y >= 0 && y < max) ? (Image) cache.get(key(zoom, x & (max - 1), y)) : null;
                    if (im != null) g.drawImage(im, px, py, Graphics.TOP | Graphics.LEFT);
                    else { g.setColor(0x4E5058); g.drawRect(px, py, T - 1, T - 1); }
                }
            }
            g.setColor(0xEE6C6C);
            g.drawLine(w / 2 - 6, h / 2, w / 2 + 6, h / 2);
            g.drawLine(w / 2, h / 2 - 6, w / 2, h / 2 + 6);
        }
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(f);
        int fh = f.getHeight();
        String s1 = "z" + zoom + "  " + status;
        if (loading) {
            String ph = phase;
            if (ph.length() > 0) s1 += ": " + ph + (phaseBytes > 0 ? " " + (phaseBytes / 1024) + " KB" : "")
                + " (" + ((System.currentTimeMillis() - lastActivity) / 1000) + " s)";
        }
        g.setColor(0x000000);
        g.fillRect(0, h - 2 * fh - 3, w, 2 * fh + 3);
        g.setColor(errors > 0 ? 0xEE6C6C : 0xFFFFFF);
        g.drawString(s1.length() > 100 ? s1.substring(0, 100) : s1, 3, h - 2 * fh - 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(0xFFFFFF);
        g.drawString(p.tileUrl.indexOf("openstreetmap") >= 0 ? "(c) OpenStreetMap contributors" : "(c) Mapy.com",
            3, h - fh - 1, Graphics.TOP | Graphics.LEFT);
    }
}
