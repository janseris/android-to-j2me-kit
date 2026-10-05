package probe;

import java.io.*;
import java.util.Vector;
import javax.microedition.io.*;
import javax.microedition.lcdui.*;

/**
 * Connection cost test: how much of a request is the new TCP + TLS connection that Java makes
 * every time. Each URL is requested several times in a row (one at a time, 300 ms apart for the
 * USB keep-alive); per request we time "open -> response code" (connect + TLS handshake + request
 * + server) and the body. Plain HTTP to the PC and to an internet server give the baseline
 * without TLS. The first HTTPS request to a host is a full handshake, the next ones may resume
 * the TLS session (see SSLLog.txt with ssladaptor_log.dll). Last: whether Java may connect to
 * 127.0.0.1 at all (needed for a native helper on the phone).
 * Results go to the log and are sent to the PC.
 */
public class ConnTest extends Canvas implements CommandListener, Runnable {
    static final String[][] TESTS = {
        // label, url, count
        { "PC http (LAN)", "http://{pc}/headers", "5" },
        { "OSM http (bez TLS, 301)", "http://tile.openstreetmap.org/0/0/0.png", "4" },
        { "OSM https dlaždice", "https://tile.openstreetmap.org/16/35362/22216.png", "5" },
        { "Mapy.com https (vectmap)", "https://vectmap.mapy.cz/rpc", "4" },
        { "Seznam foto https 200px", "https://d34-a.sdn.cz/d_34/c_img_gU_r/ItwBMU.jpeg?fl=res,,200,3", "3" },
        { "OSRM https trasa", "https://routing.openstreetmap.de/routed-foot/route/v1/driving/14.4213,50.0875%3B14.43,50.08?overview=false", "4" },
        { "Overpass https status", "https://overpass-api.de/api/status", "3" },
        { "loopback 127.0.0.1", "http://127.0.0.1:8123/", "1" },
    };
    static final Command STOP = new Command("Zpět", Command.BACK, 1);
    static final long STALL_MS = 20000;

    final Vector lines = new Vector();
    volatile boolean running = true;
    final StringBuffer report = new StringBuffer();

    ConnTest() {
        setTitle("Test spojení");
        addCommand(STOP);
        setCommandListener(this);
        new Thread(this).start();
    }

    void line(String s) {
        synchronized (lines) {
            lines.addElement(s);
            while (lines.size() > 40) lines.removeElementAt(0);
        }
        Probe.app.log(s);
        repaint();
    }

    public void run() {
        Probe p = Probe.app;
        line("Test spojení, UA " + p.userAgent);
        report.append("conn test: label | n | open->code ms (each) | body ms | bytes\n");
        for (int t = 0; t < TESTS.length && running; t++) {
            String label = TESTS[t][0];
            String url = replace(TESTS[t][1], "{pc}", p.pc);
            int n = Integer.parseInt(TESTS[t][2]);
            StringBuffer codes = new StringBuffer(), bodies = new StringBuffer();
            long first = -1, restSum = 0;
            int restN = 0, bytes = 0;
            String result = "";
            for (int i = 0; i < n && running; i++) {
                Req r = new Req(url, p.userAgent);
                r.start();
                long end = System.currentTimeMillis() + STALL_MS;
                while (!r.done && System.currentTimeMillis() < end) {
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                }
                if (!r.done) { result = "neodpovídá (" + STALL_MS / 1000 + " s), opuštěno"; codes.append("X "); break; }
                if (r.error != null) { result = r.error; codes.append("E "); if (i == 0) break; else continue; }
                result = "HTTP " + r.code;
                codes.append(r.codeMs).append(' ');
                bodies.append(r.bodyMs).append(' ');
                bytes = r.bytes;
                if (i == 0) first = r.codeMs; else { restSum += r.codeMs; restN++; }
                line("  " + label + " #" + (i + 1) + ": " + result + ", spojení+odpověď " + r.codeMs + " ms, tělo " + r.bodyMs + " ms, " + r.bytes + " B");
                try { Thread.sleep(300); } catch (InterruptedException e) {}
            }
            String sum = label + ": " + result + (first >= 0 ? ", 1. " + first + " ms" + (restN > 0 ? ", další průměr " + (restSum / restN) + " ms" : "") : "");
            line("= " + sum);
            report.append(label).append(" | ").append(n).append(" | ").append(codes).append("| ").append(bodies).append("| ").append(bytes)
                .append(" | ").append(result).append('\n');
        }
        line("Hotovo. Odesílám na PC...");
        Probe.app.log(report.toString());
        try {
            line(Probe.post("http://" + p.pc + "/results?name=conntest", report.toString() + "\n--- log ---\n" + Probe.app.logText()));
        } catch (Throwable e) {
            line("Odeslání selhalo: " + e + " (Menu: Odeslat log na PC)");
        }
    }

    static String replace(String s, String a, String b) {
        int i = s.indexOf(a);
        return i < 0 ? s : s.substring(0, i) + b + s.substring(i + a.length());
    }

    /** One request on its own thread (a stuck one is abandoned, never closed from here). */
    static class Req extends Thread {
        final String url, ua;
        volatile boolean done;
        int code, bytes;
        long codeMs, bodyMs;
        String error;

        Req(String url, String ua) { this.url = url; this.ua = ua; }

        public void run() {
            HttpConnection c = null;
            InputStream in = null;
            try {
                long t0 = System.currentTimeMillis();
                c = (HttpConnection) Connector.open(url);
                c.setRequestProperty("User-Agent", ua);
                code = c.getResponseCode();
                long t1 = System.currentTimeMillis();
                codeMs = t1 - t0;
                in = c.openInputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) > 0) bytes += n;
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
                g.setColor(s.startsWith("=") ? 0xFCEE74 : 0xDBDEE1);
                g.drawString(s, 3, (i - from) * fh, Graphics.TOP | Graphics.LEFT);
            }
        }
    }
}
