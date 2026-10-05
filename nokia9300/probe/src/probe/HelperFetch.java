package probe;

import java.io.*;
import java.util.Vector;
import javax.microedition.io.*;
import javax.microedition.lcdui.*;

/**
 * Net Helper fetch test: the same tiles directly (HttpConnection, a new connection for each)
 * and through Net Helper 9300 (http://127.0.0.1:8123/fetch?u=..., which keeps its connections
 * to the servers open). After each helper series one more tile after 15 s idle shows whether the
 * server kept the connection open. Results go to the PC (uploads/..._helperfetch.txt).
 */
public class HelperFetch extends Canvas implements CommandListener, Runnable {
    static final Command STOP = new Command("Zpět", Command.BACK, 1);
    static final String HELPER = "http://127.0.0.1:8123/fetch?u=";
    static final int N = 6;

    final Vector lines = new Vector();
    volatile boolean running = true;
    final StringBuffer report = new StringBuffer();
    String ua;

    HelperFetch() {
        setTitle("Net Helper: dlaždice");
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

    static String[] urls(String base, String fmt, int x0, int y) {
        String[] u = new String[N + 1];
        for (int i = 0; i <= N; i++) u[i] = base + BigTest.replace(BigTest.replace(fmt, "{x}", "" + (x0 + i)), "{y}", "" + y);
        return u;
    }

    public void run() {
        ua = Probe.app.userAgent;
        line("Net Helper fetch test, " + System.getProperty("microedition.platform"));
        try {
            String cuzk = "/arcgis1/rest/services/ZTM_WM/MapServer/tile/15/{y}/{x}";
            // each set uses other tiles than the previous run of the same server, so nothing is cached
            // directly one row of tiles, through the helper the next row (so neither gets the
            // other's server-side cache); the helper's tiles are checked by decoding them
            compare("OpenTopoMap http", "http://tile.opentopomap.org", "/15/{x}/{y}.png", 17700, 11114, true);
            compare("ČÚZK http", "http://ags.cuzk.gov.cz", cuzk, 17700, 11114, false);
            compare("OSM https", "https://tile.openstreetmap.org", "/15/{x}/{y}.png", 17700, 11116, true);
            compare("ČÚZK https", "https://ags.cuzk.gov.cz", cuzk, 17710, 11116, false);
        } catch (Throwable e) {
            line("CHYBA: " + e);
        }
        line("Hotovo, odesílám na PC...");
        Probe.app.saveLog();
        try {
            line(Probe.post("http://" + Probe.app.pc + "/results?name=helperfetch", report.toString()));
        } catch (Throwable e) {
            line("Odeslání selhalo: " + e);
        }
    }

    static String encode(String s) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') b.append(c);
            else {
                String h = Integer.toHexString(c & 0xff).toUpperCase();
                b.append('%').append(h.length() < 2 ? "0" + h : h);
            }
        }
        return b.toString();
    }

    /** { ms, code, bytes, X-Helper or image check } or the error text in [3] with code -1. */
    String[] get(String url, boolean viaHelper, boolean sendUa) {
        HttpConnection c = null;
        InputStream in = null;
        long t0 = System.currentTimeMillis();
        try {
            c = (HttpConnection) Connector.open(viaHelper ? HELPER + encode(url) : url);
            if (sendUa) c.setRequestProperty(viaHelper ? "X-Ua" : "User-Agent", ua);
            int code = c.getResponseCode();
            in = c.openInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            String h = viaHelper ? c.getHeaderField("X-Helper") : null;
            if (viaHelper && code == 200) {
                try {
                    Image img = Image.createImage(o.toByteArray(), 0, o.size());
                    h = h + ", obrázek " + img.getWidth() + "x" + img.getHeight();
                } catch (Throwable e) {
                    h = h + ", NENÍ OBRÁZEK: " + e;
                }
            }
            if (code != 200 && viaHelper) {
                String e = c.getHeaderField("X-Helper-Error");
                if (e != null) h = h + " ERROR " + e;
            }
            return new String[] { "" + (System.currentTimeMillis() - t0), "" + code, "" + o.size(), h == null ? "" : h };
        } catch (Throwable e) {
            return new String[] { "" + (System.currentTimeMillis() - t0), "-1", "0", e.toString() };
        } finally {
            try { if (in != null) in.close(); } catch (Throwable e) {}
            try { if (c != null) c.close(); } catch (Throwable e) {}
        }
    }

    void compare(String name, String base, String fmt, int x0, int y, boolean sendUa) {
        if (!running) return;
        String[] urls = urls(base, fmt, x0, y), next = urls(base, fmt, x0, y + 1);
        line("== " + name + ", " + N + " dlaždic");
        // 1. directly, new connection for each (as Mapy does now)
        StringBuffer t = new StringBuffer();
        long total = 0, bytes = 0;
        for (int i = 0; i < N && running; i++) {
            String[] r = get(urls[i], false, sendUa);
            t.append(r[0]).append(r[1].equals("200") ? "" : "(" + r[1] + ")").append(' ');
            if (r[1].equals("-1")) line("  přímo chyba: " + r[3]);
            total += Long.parseLong(r[0]);
            bytes += Long.parseLong(r[2]);
            BigTest.pause(50);
        }
        line("přímo: [" + t.toString().trim() + "] ms, součet " + total + " ms, " + bytes / 1024 + " KB");
        BigTest.pause(500);
        // 2. through Net Helper
        t = new StringBuffer();
        total = 0;
        bytes = 0;
        for (int i = 0; i < N && running; i++) {
            String[] r = get(next[i], true, sendUa);
            t.append(r[0]).append(r[1].equals("200") ? "" : "(" + r[1] + ")").append(' ');
            line("  " + (i + 1) + ": " + r[0] + " ms, HTTP " + r[1] + ", " + r[2] + " B, " + r[3]);
            total += Long.parseLong(r[0]);
            bytes += Long.parseLong(r[2]);
            BigTest.pause(50);
        }
        line("Net Helper: [" + t.toString().trim() + "] ms, součet " + total + " ms, " + bytes / 1024 + " KB");
        // 3. one more after 15 s of nothing: is the connection still open?
        line("  15 s nic...");
        BigTest.pause(15000);
        if (!running) return;
        String[] r = get(next[N], true, sendUa);
        line("  po 15 s: " + r[0] + " ms, HTTP " + r[1] + ", " + r[2] + " B, " + r[3]);
        BigTest.pause(500);
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
