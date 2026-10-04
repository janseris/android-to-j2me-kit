package probe;

import java.io.*;
import javax.microedition.io.*;
import javax.microedition.lcdui.*;

/**
 * Downloads the map tiles that cover the screen (one request at a time), logs size, type, download
 * and decode time, and draws them. Arrows pan by half a tile, keys 1/3 or star/hash zoom, 5 = GPS position.
 */
class TileScreen extends Canvas implements CommandListener, Runnable {
    static final Command RELOAD = new Command("Načíst znovu", Command.SCREEN, 1);
    static final Command GPS = new Command("Na polohu GPS", Command.SCREEN, 2);
    static final int T = 256;

    final Probe p = Probe.app;
    int zoom = 13;
    double cx, cy;              // centre in world pixels at zoom
    Image[] tiles = new Image[0];
    int[] tx = new int[0], ty = new int[0];
    String status = "";
    volatile boolean loading;
    int bytesTotal; long msTotal;

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
        if (c == Probe.BACK) p.back();
        else if (c == RELOAD) load();
        else if (c == GPS && Nmea.lat != 0) { center(Nmea.lat, Nmea.lon); load(); }
    }

    protected void keyPressed(int key) {
        if (loading) return;
        int a = 0;
        try { a = getGameAction(key); } catch (Throwable e) {}
        if (a == LEFT) cx -= T / 2; else if (a == RIGHT) cx += T / 2;
        else if (a == UP) cy -= T / 2; else if (a == DOWN) cy += T / 2;
        else if ((key == '3' || key == '*') && zoom < 18) { zoom++; cx *= 2; cy *= 2; }
        else if ((key == '1' || key == '#') && zoom > 3) { zoom--; cx /= 2; cy /= 2; }
        else if (key == '5' && Nmea.lat != 0) center(Nmea.lat, Nmea.lon);
        else return;
        load();
    }

    void load() {
        if (loading) return;
        loading = true;
        new Thread(this).start();
    }

    public void run() {
        int w = getWidth(), h = getHeight();
        int x0 = (int) Math.floor((cx - w / 2) / T), x1 = (int) Math.floor((cx + w / 2) / T);
        int y0 = (int) Math.floor((cy - h / 2) / T), y1 = (int) Math.floor((cy + h / 2) / T);
        int count = (x1 - x0 + 1) * (y1 - y0 + 1);
        Image[] im = new Image[count];
        int[] ax = new int[count], ay = new int[count];
        tiles = new Image[0];
        System.gc();
        // OSM tile policy: only tiles the user is looking at, no prefetch, attribution visible
        p.log("--- tiles z" + zoom + " x " + x0 + ".." + x1 + " y " + y0 + ".." + y1 + " (" + count + "), screen " + w + "x" + h);
        bytesTotal = 0; msTotal = 0;
        int k = 0, max = 1 << zoom;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                ax[k] = x; ay[k] = y;
                status = "dlaždice " + (k + 1) + "/" + count + "...";
                repaint();
                if (y >= 0 && y < max) im[k] = fetch(x & (max - 1), y);
                k++;
            }
        }
        tx = ax; ty = ay; tiles = im;
        status = count + " dlaždic, " + (bytesTotal / 1024) + " KB, " + msTotal + " ms";
        p.memLine("after tiles");
        loading = false;
        repaint();
    }

    Image fetch(int x, int y) {
        String url = replace(replace(replace(replace(p.tileUrl, "{z}", "" + zoom), "{x}", "" + x), "{y}", "" + y), "{key}", p.apiKey);
        long t0 = System.currentTimeMillis();
        HttpConnection c = null;
        InputStream in = null;
        try {
            c = (HttpConnection) Connector.open(url);
            if (p.userAgent.length() > 0) c.setRequestProperty("User-Agent", p.userAgent);
            int code = c.getResponseCode();
            long tResp = System.currentTimeMillis();
            String type = c.getType();
            int len = (int) c.getLength();
            in = c.openInputStream();
            byte[] b = readAll(in, len);
            long tBody = System.currentTimeMillis();
            bytesTotal += b.length;
            msTotal += tBody - t0;
            String magic = magic(b);
            if (code != 200) {
                p.log("tile " + zoom + "/" + x + "/" + y + ": HTTP " + code + " " + type + " " + b.length + " B: "
                    + new String(b, 0, Math.min(b.length, 200)));
                return null;
            }
            long td0 = System.currentTimeMillis();
            Image im = Image.createImage(b, 0, b.length);
            long td = System.currentTimeMillis() - td0;
            p.log("tile " + zoom + "/" + x + "/" + y + ": " + b.length + " B " + type + " (" + magic + "), response "
                + (tResp - t0) + " ms, body " + (tBody - tResp) + " ms, decode " + td + " ms");
            return im;
        } catch (Throwable e) {
            p.log("tile " + zoom + "/" + x + "/" + y + " failed after " + (System.currentTimeMillis() - t0) + " ms: " + e);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable e) {}
            try { if (c != null) c.close(); } catch (Throwable e) {}
        }
    }

    static byte[] readAll(InputStream in, int len) throws IOException {
        if (len > 0) {
            byte[] b = new byte[len];
            int n = 0;
            while (n < len) {
                int r = in.read(b, n, len - n);
                if (r < 0) break;
                n += r;
            }
            if (n == len) return b;
            byte[] c = new byte[n];
            System.arraycopy(b, 0, c, 0, n);
            return c;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int r;
        while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        return o.toByteArray();
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

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0x2B2D31);
        g.fillRect(0, 0, w, h);
        Image[] im = tiles;
        int ox = (int) cx - w / 2, oy = (int) cy - h / 2;
        for (int i = 0; i < im.length; i++) {
            int px = tx[i] * T - ox, py = ty[i] * T - oy;
            if (im[i] != null) g.drawImage(im[i], px, py, Graphics.TOP | Graphics.LEFT);
            else { g.setColor(0x4E5058); g.drawRect(px, py, T - 1, T - 1); }
        }
        // centre cross
        g.setColor(0xEE6C6C);
        g.drawLine(w / 2 - 6, h / 2, w / 2 + 6, h / 2);
        g.drawLine(w / 2, h / 2 - 6, w / 2, h / 2 + 6);
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(f);
        String s = "z" + zoom + "  " + status + "   " + (p.tileUrl.indexOf("openstreetmap") >= 0 ? "(c) OpenStreetMap contributors" : "(c) Mapy.com");
        g.setColor(0x000000);
        g.fillRect(0, h - f.getHeight() - 2, f.stringWidth(s) + 6, f.getHeight() + 2);
        g.setColor(0xFFFFFF);
        g.drawString(s, 3, h - f.getHeight() - 1, Graphics.TOP | Graphics.LEFT);
    }
}
