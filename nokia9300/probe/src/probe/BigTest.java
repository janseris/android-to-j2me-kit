package probe;

import java.io.*;
import java.util.Vector;
import javax.microedition.io.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.RecordStore;

/**
 * The big test: what makes the map app's requests slow and what could make them faster.
 *  1. Network: the same data over plain HTTP and HTTPS from every server the map app uses, several
 *     times in a row (connect+request time, body time), and once without the 300 ms pause.
 *  2. Image decoding: PNG tiles (OSM, OpenTopoMap) vs JPEG tiles (ČÚZK) vs a photo.
 *  3. Phone storage (RMS, the disk cache): write and read 30 KB.
 *  4. What a native helper would need: HTTP to 127.0.0.1, and whether an unsigned MIDlet may
 *     open a raw socket (socket://) at all - the phone may ask for permission: answer it.
 * Everything goes to the log and is sent to the PC (uploads/..._bigtest.txt) at the end.
 */
public class BigTest extends Canvas implements CommandListener, Runnable {
    static final Command STOP = new Command("Zpět", Command.BACK, 1);
    static final long STALL_MS = 20000;
    static final String CUZK = "ags.cuzk.gov.cz/arcgis1/rest/services/";

    // label, url without scheme, schemes ("h" http, "s" https), count, user agent (no = only the phone's)
    static final String[][] NET = {
        { "PC (LAN)", "{pc}/headers", "h", "4", "ua" },
        { "OpenTopoMap tile", "tile.opentopomap.org/15/17696/11110.png", "hs", "3", "ua" },
        { "OSM tile", "tile.openstreetmap.org/15/17696/11110.png", "s", "3", "ua" },
        { "ČÚZK základní tile", CUZK + "ZTM_WM/MapServer/tile/15/11110/17696", "hs", "3", "no" },
        { "ČÚZK ortofoto tile", CUZK + "ORTOFOTO_WM/MapServer/tile/15/11110/17696", "hs", "3", "no" },
        { "Seznam photo 200px", "d34-a.sdn.cz/d_34/c_img_gU_r/ItwBMU.jpeg?fl=res,,200,3", "s", "2", "ua" },
        { "Mapy.com RPC (GET)", "vectmap.mapy.cz/rpc", "s", "3", "ua" },
        { "OSRM route", "routing.openstreetmap.de/routed-car/route/v1/driving/14.42,50.08%3B14.43,50.08?overview=false", "hs", "3", "ua" },
        { "Overpass main status", "overpass-api.de/api/status", "hs", "2", "ua" },
        { "Overpass private.coffee status", "overpass.private.coffee/api/status", "hs", "2", "ua" },
        { "Overpass mail.ru status", "maps.mail.ru/osm/tools/overpass/api/status", "hs", "2", "ua" },
    };

    final Vector lines = new Vector();
    volatile boolean running = true;
    final StringBuffer report = new StringBuffer();
    final Vector images = new Vector();     // { label, byte[] } for the decode test

    BigTest() {
        setTitle("Velký test");
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
        Probe p = Probe.app;
        Runtime rt = Runtime.getRuntime();
        line("Velký test, " + System.getProperty("microedition.platform") + ", heap " + rt.totalMemory() / 1024 + " KB, free " + rt.freeMemory() / 1024 + " KB");
        try {
            network(p);
            if (running) noPause(p);
            if (running) decode();
            if (running) storage();
            if (running) helper(p);
        } catch (Throwable e) {
            line("CHYBA testu: " + e);
        }
        line("Hotovo, odesílám na PC...");
        Probe.app.saveLog();
        try {
            line(Probe.post("http://" + p.pc + "/results?name=bigtest", report.toString()));
        } catch (Throwable e) {
            line("Odeslání selhalo: " + e + " (Menu: Odeslat log na PC)");
        }
    }

    // ------------------------------------------------------------ 1. network

    void network(Probe p) {
        line("== 1. síť: připojení+odpověď / tělo (ms), stejná data přes http a https");
        for (int t = 0; t < NET.length && running; t++) {
            String[] r = NET[t];
            String path = replace(r[1], "{pc}", p.pc);
            int n = Integer.parseInt(r[3]);
            String ua = r[4].equals("ua") ? p.userAgent : null;
            for (int k = 0; k < r[2].length() && running; k++) {
                String scheme = r[2].charAt(k) == 'h' ? "http" : "https";
                StringBuffer times = new StringBuffer();
                long first = -1, rest = 0;
                int restN = 0;
                String result = "";
                byte[] last = null;
                for (int i = 0; i < n && running; i++) {
                    Req q = request(scheme + "://" + path, ua);
                    if (q == null) { result = "neodpovídá " + STALL_MS / 1000 + " s"; break; }
                    if (q.error != null) { result = q.error; break; }
                    result = "HTTP " + q.code + ", " + q.bytes + " B";
                    times.append(q.codeMs).append('/').append(q.bodyMs).append(' ');
                    if (i == 0) first = q.codeMs; else { rest += q.codeMs; restN++; }
                    last = q.body;
                    pause(300);
                }
                line(r[0] + " " + scheme + ": " + result + (first >= 0 ? "; 1. " + first + " ms" + (restN > 0 ? ", další ø " + rest / restN + " ms" : "") : "")
                    + (times.length() > 0 ? " [" + times.toString().trim() + "]" : ""));
                if (last != null && last.length > 100 && (r[0].indexOf("tile") >= 0 || r[0].indexOf("photo") >= 0) && k == r[2].length() - 1)
                    images.addElement(new Object[] { r[0], last });
            }
        }
    }

    /** Does the 300 ms pause after each request cost much / matter? 5 requests back to back. */
    void noPause(Probe p) {
        line("== 1b. 5x OpenTopoMap http bez pauzy mezi požadavky");
        StringBuffer times = new StringBuffer();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 5 && running; i++) {
            Req q = request("http://tile.opentopomap.org/15/17696/11110.png", p.userAgent);
            if (q == null || q.error != null) { times.append(q == null ? "X " : "E(" + q.error + ") "); break; }
            times.append(q.codeMs).append('/').append(q.bodyMs).append(' ');
        }
        line("bez pauzy: [" + times.toString().trim() + "], celkem " + (System.currentTimeMillis() - t0) + " ms");
        pause(1000);
    }

    Req request(String url, String ua) {
        Req q = new Req(url, ua);
        q.start();
        long end = System.currentTimeMillis() + STALL_MS;
        while (!q.done && System.currentTimeMillis() < end) pause(50);
        return q.done ? q : null;      // a stuck request is abandoned, never closed from here
    }

    static class Req extends Thread {
        final String url, ua;
        volatile boolean done;
        int code, bytes;
        long codeMs, bodyMs;
        String error;
        byte[] body;

        Req(String url, String ua) { this.url = url; this.ua = ua; }

        public void run() {
            HttpConnection c = null;
            InputStream in = null;
            try {
                long t0 = System.currentTimeMillis();
                c = (HttpConnection) Connector.open(url);
                if (ua != null) c.setRequestProperty("User-Agent", ua);
                code = c.getResponseCode();
                long t1 = System.currentTimeMillis();
                codeMs = t1 - t0;
                in = c.openInputStream();
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                body = o.toByteArray();
                bytes = body.length;
                bodyMs = System.currentTimeMillis() - t1;
            } catch (Throwable e) {
                error = e.toString();
            } finally {
                try { if (in != null) in.close(); } catch (Throwable e) {}
                try { if (c != null) c.close(); } catch (Throwable e) {}
                done = true;
            }
        }
    }

    // ------------------------------------------------------------ 2. decoding

    void decode() {
        line("== 2. dekódování obrázků (3x, ms)");
        for (int i = 0; i < images.size() && running; i++) {
            Object[] im = (Object[]) images.elementAt(i);
            byte[] b = (byte[]) im[1];
            StringBuffer t = new StringBuffer();
            String size = "";
            for (int k = 0; k < 3; k++) {
                long t0 = System.currentTimeMillis();
                try {
                    Image img = Image.createImage(b, 0, b.length);
                    size = img.getWidth() + "x" + img.getHeight();
                    t.append(System.currentTimeMillis() - t0).append(' ');
                } catch (Throwable e) {
                    t.append("E(").append(e).append(") ");
                    break;
                }
            }
            line((String) im[0] + ": " + b.length + " B " + (b.length > 3 && b[1] == 'P' ? "PNG" : "JPEG") + " " + size + " -> [" + t.toString().trim() + "]");
        }
        images.removeAllElements();
        System.gc();
    }

    // ------------------------------------------------------------ 3. storage

    void storage() {
        line("== 3. úložiště telefonu (RMS): 30 KB zápis / čtení (ms)");
        byte[] b = new byte[30000];
        for (int i = 0; i < b.length; i++) b[i] = (byte) i;
        StringBuffer w = new StringBuffer(), r = new StringBuffer();
        try {
            RecordStore rs = RecordStore.openRecordStore("bigtest", true);
            for (int i = 0; i < 4; i++) {
                long t0 = System.currentTimeMillis();
                int id = rs.addRecord(b, 0, b.length);
                w.append(System.currentTimeMillis() - t0).append(' ');
                t0 = System.currentTimeMillis();
                rs.getRecord(id);
                r.append(System.currentTimeMillis() - t0).append(' ');
                rs.deleteRecord(id);
            }
            rs.closeRecordStore();
            RecordStore.deleteRecordStore("bigtest");
            line("zápis [" + w.toString().trim() + "], čtení [" + r.toString().trim() + "]");
        } catch (Throwable e) {
            line("RMS: " + e);
        }
    }

    // ------------------------------------------------------------ 4. native helper prerequisites

    void helper(Probe p) {
        line("== 4. pro nativního pomocníka");
        Req q = request("http://127.0.0.1:8123/", p.userAgent);
        line("http://127.0.0.1:8123 -> " + (q == null ? "neodpovídá" : q.error != null ? q.error : "HTTP " + q.code)
            + "  (odmítnuto spojení = smí, jen tam nic neběží; SecurityException = nesmí)");
        int colon = p.pc.indexOf(':');
        String host = colon < 0 ? p.pc : p.pc.substring(0, colon);
        int port = colon < 0 ? 80 : Integer.parseInt(p.pc.substring(colon + 1));
        String url = "socket://" + host + ":" + (port + 1);
        line("socket:// (" + url + "), telefon se může zeptat na povolení...");
        StreamConnection s = null;
        try {
            long t0 = System.currentTimeMillis();
            s = (StreamConnection) Connector.open(url);
            OutputStream o = s.openOutputStream();
            o.write(("GET /socket-test HTTP/1.0\r\nHost: " + host + "\r\n\r\n").getBytes());
            o.flush();
            InputStream in = s.openInputStream();
            int n = 0;
            while (in.read() >= 0) n++;
            line("socket:// FUNGUJE: " + n + " B odpovědi za " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable e) {
            line("socket:// nejde: " + e);
        } finally {
            try { if (s != null) s.close(); } catch (Throwable e) {}
        }
        Runtime rt = Runtime.getRuntime();
        line("paměť na konci: heap " + rt.totalMemory() / 1024 + " KB, volno " + rt.freeMemory() / 1024 + " KB");
    }

    // ------------------------------------------------------------ helpers, UI

    static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }

    static String replace(String s, String a, String b) {
        int i = s.indexOf(a);
        return i < 0 ? s : s.substring(0, i) + b + s.substring(i + a.length());
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
