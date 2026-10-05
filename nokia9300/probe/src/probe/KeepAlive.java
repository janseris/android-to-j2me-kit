package probe;

import java.io.*;
import java.util.Vector;
import javax.microedition.io.*;
import javax.microedition.lcdui.*;

/**
 * Keep-alive test. The big test showed that an unsigned MIDlet may open socket:// on the 9300.
 * So the app could speak HTTP/1.1 itself and send many requests over ONE connection, instead of
 * HttpConnection's new connection (and, for HTTPS, new TLS handshake) per request.
 * Here: 6 different tiles over one socket:// (plain HTTP) and over one ssl:// (the phone's TLS)
 * connection, each compared with the same 6 tiles through HttpConnection.
 * Results are sent to the PC (uploads/..._keepalive.txt).
 */
public class KeepAlive extends Canvas implements CommandListener, Runnable {
    static final Command STOP = new Command("Zpět", Command.BACK, 1);
    static final String CUZK = "/arcgis1/rest/services/ZTM_WM/MapServer/tile/15/";

    final Vector lines = new Vector();
    volatile boolean running = true;
    final StringBuffer report = new StringBuffer();
    String ua;

    KeepAlive() {
        setTitle("Keep-alive test");
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

    static String[] otm() {
        String[] p = new String[6];
        for (int i = 0; i < 6; i++) p[i] = "/15/" + (17690 + i) + "/11110.png";
        return p;
    }

    static String[] cuzk() {
        String[] p = new String[6];
        for (int i = 0; i < 6; i++) p[i] = CUZK + "11110/" + (17690 + i);
        return p;
    }

    static String[] osm() {
        String[] p = new String[6];
        for (int i = 0; i < 6; i++) p[i] = "/15/" + (17690 + i) + "/11111.png";
        return p;
    }

    public void run() {
        ua = Probe.app.userAgent;
        line("Keep-alive test, " + System.getProperty("microedition.platform"));
        try {
            // plain HTTP: HttpConnection per tile vs one socket for all
            compare("OpenTopoMap", "tile.opentopomap.org", otm(), false, true);
            compare("ČÚZK základní", "ags.cuzk.gov.cz", cuzk(), false, false);
            // HTTPS: HttpConnection per tile vs one ssl:// connection
            compare("OSM", "tile.openstreetmap.org", osm(), true, true);
            compare("ČÚZK základní", "ags.cuzk.gov.cz", cuzk(), true, false);
        } catch (Throwable e) {
            line("CHYBA: " + e);
        }
        line("Hotovo, odesílám na PC...");
        Probe.app.saveLog();
        try {
            line(Probe.post("http://" + Probe.app.pc + "/results?name=keepalive", report.toString()));
        } catch (Throwable e) {
            line("Odeslání selhalo: " + e);
        }
    }

    void compare(String name, String host, String[] paths, boolean tls, boolean sendUa) {
        if (!running) return;
        String scheme = tls ? "https" : "http";
        line("== " + name + " " + scheme + ", " + paths.length + " dlaždic");
        // 1. HttpConnection, a new connection for each tile (with the usual 300 ms pause)
        long t0 = System.currentTimeMillis();
        StringBuffer t = new StringBuffer();
        long bytes = 0;
        for (int i = 0; i < paths.length && running; i++) {
            BigTest.Req q = new BigTest.Req(scheme + "://" + host + paths[i], sendUa ? ua : null);
            long s = System.currentTimeMillis();
            q.start();
            long end = s + 20000;
            while (!q.done && System.currentTimeMillis() < end) BigTest.pause(20);
            if (!q.done || q.error != null) { t.append(q.done ? "E " : "X "); line("  HttpConnection chyba: " + (q.done ? q.error : "timeout")); break; }
            t.append(System.currentTimeMillis() - s).append(' ');
            bytes += q.bytes;
            BigTest.pause(300);
        }
        line("HttpConnection (nové spojení na každou): [" + t.toString().trim() + "] ms, celkem " + (System.currentTimeMillis() - t0) + " ms, " + bytes / 1024 + " KB");
        BigTest.pause(1000);
        // 2. one socket / ssl connection, HTTP/1.1 keep-alive, requests one after another
        String url = (tls ? "ssl://" : "socket://") + host + (tls ? ":443" : ":80");
        StreamConnection c = null;
        t0 = System.currentTimeMillis();
        t = new StringBuffer();
        bytes = 0;
        int reconnects = 0;
        try {
            c = (StreamConnection) Connector.open(url);
            Buf in = new Buf(c.openInputStream());
            OutputStream out = c.openOutputStream();
            t.append("spojení ").append(System.currentTimeMillis() - t0).append(" | ");
            for (int i = 0; i < paths.length && running; i++) {
                long s = System.currentTimeMillis();
                String req = "GET " + paths[i] + " HTTP/1.1\r\nHost: " + host + "\r\n"
                    + (sendUa ? "User-Agent: " + ua + "\r\n" : "") + "Connection: keep-alive\r\n\r\n";
                out.write(req.getBytes());
                out.flush();
                int[] r = readResponse(in);       // { status, body bytes, connection closed? }
                t.append(System.currentTimeMillis() - s).append(r[0] == 200 ? "" : "(" + r[0] + ")").append(' ');
                bytes += r[1];
                if (r[2] == 1 && i + 1 < paths.length) {
                    // the server closed the connection: note it and open a new one
                    reconnects++;
                    try { c.close(); } catch (Throwable e) {}
                    c = (StreamConnection) Connector.open(url);
                    in = new Buf(c.openInputStream());
                    out = c.openOutputStream();
                    t.append("[nové spojení] ");
                }
            }
            line(url + " (jedno spojení, keep-alive): [" + t.toString().trim() + "] ms, celkem "
                + (System.currentTimeMillis() - t0) + " ms, " + bytes / 1024 + " KB" + (reconnects > 0 ? ", server zavřel " + reconnects + "x" : ""));
        } catch (Throwable e) {
            line(url + ": " + e + " (po: " + t.toString().trim() + ")");
        } finally {
            try { if (c != null) c.close(); } catch (Throwable e) {}
        }
        BigTest.pause(1000);
    }

    /** Reads one HTTP/1.1 response: status line, headers, body (Content-Length or chunked). */
    /** Buffered reading (block reads, never byte by byte from the socket). */
    static class Buf {
        final InputStream in;
        final byte[] b = new byte[4096];
        int p, n;

        Buf(InputStream in) { this.in = in; }

        int read() throws IOException {
            if (p >= n) {
                n = in.read(b, 0, b.length);
                p = 0;
                if (n <= 0) { n = 0; return -1; }
            }
            return b[p++] & 0xff;
        }

        int read(byte[] dst, int off, int len) throws IOException {
            if (p >= n) {
                n = in.read(b, 0, b.length);
                p = 0;
                if (n <= 0) { n = 0; return -1; }
            }
            int k = Math.min(len, n - p);
            System.arraycopy(b, p, dst, off, k);
            p += k;
            return k;
        }
    }

    static int[] readResponse(Buf in) throws IOException {
        String status = readLine(in);
        int code = 0;
        try { code = Integer.parseInt(status.substring(9, 12)); } catch (Throwable e) {}
        int len = -1;
        boolean chunked = false, close = false;
        String h;
        while ((h = readLine(in)).length() > 0) {
            String l = h.toLowerCase();
            if (l.startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
            else if (l.startsWith("transfer-encoding:") && l.indexOf("chunked") > 0) chunked = true;
            else if (l.startsWith("connection:") && l.indexOf("close") > 0) close = true;
        }
        int total = 0;
        byte[] buf = new byte[2048];
        if (chunked) {
            while (true) {
                String sz = readLine(in).trim();
                int semi = sz.indexOf(';');
                int n = Integer.parseInt(semi < 0 ? sz : sz.substring(0, semi), 16);
                if (n == 0) { while (readLine(in).length() > 0) {} break; }
                total += skip(in, n, buf);
                readLine(in);
            }
        } else if (len >= 0) {
            total = skip(in, len, buf);
        } else {
            int n;
            while ((n = in.read(buf, 0, buf.length)) > 0) total += n;
            close = true;
        }
        return new int[] { code, total, close ? 1 : 0 };
    }

    static int skip(Buf in, int n, byte[] buf) throws IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(buf, 0, Math.min(buf.length, n - got));
            if (r < 0) throw new EOFException("konec po " + got + " z " + n + " B");
            got += r;
        }
        return got;
    }

    static String readLine(Buf in) throws IOException {
        StringBuffer b = new StringBuffer();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.append((char) c);
        }
        if (c < 0 && b.length() == 0) throw new EOFException("spojení zavřeno");
        return b.toString();
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
