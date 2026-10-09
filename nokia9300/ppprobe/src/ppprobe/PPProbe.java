package ppprobe;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.Properties;

/**
 * PP Probe: what the Nokia 9300's second Java, Personal Profile 1.0 (IBM J9, AWT), can do for a
 * map app, measured the same way as Probe (the MIDP one) so the two compare:
 *   1. the VM, memory and screen;
 *   2. plain files (java.io): 30 tile-sized files written and read on each drive;
 *   3. the network: Net Helper on 127.0.0.1 (raw socket and URL), a map server directly, the GPS;
 *   4. decoding one map tile 5 times (the same tile Probe 3.8 decodes) and drawing it.
 * Started from ppprobe.j9 (the J9 command line) in the File manager. Results go to the PC
 * (uploads/..._pptest.txt) and to C:\Data\PPProbe\result.txt. Written for Java 1.3-level APIs only.
 */
public class PPProbe extends Frame implements Runnable {
    static final String VERSION = "0.2";
    static final String UA = "PPProbe9300/" + VERSION + " (Java Personal Profile test; Nokia 9300; SymbianOS/7.0s Series80/2.0)";
    static final int N = 30, SIZE = 20 * 1024;
    /** The tile Probe 3.8 decodes too (Brno, zoom 16). */
    static final String TILE = "https://tile.openstreetmap.org/16/35803/22210.png";
    static final String TOPO = "http://tile.opentopomap.org/16/";

    final TextArea text = new TextArea("", 20, 60, TextArea.SCROLLBARS_VERTICAL_ONLY);
    final StringBuffer report = new StringBuffer();
    final StringBuffer summary = new StringBuffer();
    String pc;
    byte[] tile;

    public static void main(String[] args) {
        PPProbe p = new PPProbe();
        p.pc = System.getProperty("pc", args.length > 0 ? args[0] : "192.168.137.1:8000");
        p.start();
    }

    PPProbe() {
        super("PP Probe " + VERSION);
        text.setEditable(false);
        setLayout(new BorderLayout());
        add(text, BorderLayout.CENTER);
        Button exit = new Button("Exit");
        exit.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { System.exit(0); }
        });
        add(exit, BorderLayout.SOUTH);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { System.exit(0); }
        });
    }

    void start() {
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(d.width, d.height);
        setVisible(true);
        new Thread(this).start();
    }

    void line(String s) {
        report.append(s).append('\n');
        System.out.println(s);
        try { text.append(s + "\n"); } catch (Throwable e) {}
    }

    static String p(long ms) { return ms + " ms" + (ms > 1500 ? " (prompt?)" : ""); }

    static String times(long[] a) {
        StringBuffer s = new StringBuffer("[");
        for (int i = 0; i < a.length; i++) s.append(i > 0 ? " " : "").append(a[i]);
        return s.append(']').toString();
    }

    static long avg(long[] a) {
        if (a.length < 2) return a.length == 1 ? a[0] : 0;
        long s = 0;
        for (int i = 1; i < a.length; i++) s += a[i];
        return s / (a.length - 1);
    }

    public void run() {
        line("== PP Probe " + VERSION + ": Java Personal Profile on the phone");
        try { vm(); } catch (Throwable e) { line("VM info: " + e); }
        try { files(); } catch (Throwable e) { line("files: " + e); }
        try { network(); } catch (Throwable e) { line("network: " + e); }
        try { decode(); } catch (Throwable e) { line("decoding: " + e); }
        line("== Summary");
        line(summary.toString().trim());
        try { saveFile(); } catch (Throwable e) { line("(saving result.txt failed: " + e + ")"); }
        try { line("sent to PC: " + post("http://" + pc + "/results?name=pptest", report.toString())); }
        catch (Throwable e) { line("(sending to PC " + pc + " failed: " + e + ")"); }
        line("Done. Exit closes PP Probe.");
    }

    // ---- 1. the VM ----
    void vm() {
        String[] keys = { "java.version", "java.vendor", "java.vm.name", "java.vm.version", "java.specification.version",
            "os.name", "os.version", "os.arch", "microedition.platform", "microedition.configuration",
            "com.ibm.oti.configuration", "com.ibm.oti.vm.library.version", "java.class.path", "user.dir", "java.home",
            "file.separator", "file.encoding" };
        for (int i = 0; i < keys.length; i++) {
            String v = null;
            try { v = System.getProperty(keys[i]); } catch (Throwable e) { v = "(" + e + ")"; }
            if (v != null) line(keys[i] + " = " + v);
        }
        Runtime r = Runtime.getRuntime();
        line("memory: total " + r.totalMemory() / 1024 + " KB, free " + r.freeMemory() / 1024 + " KB");
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        line("screen " + d.width + "x" + d.height + ", colour model " + Toolkit.getDefaultToolkit().getColorModel());
        summary.append("VM: ").append(System.getProperty("java.vm.name")).append(' ').append(System.getProperty("java.vm.version"))
            .append(", heap ").append(r.totalMemory() / 1024).append(" KB\n");
        // how much heap we can really get: 256x256 RGB pictures (256 KB each) until it runs out
        int[][] keep = new int[100][];
        int n = 0;
        long t = System.currentTimeMillis();
        try {
            for (; n < keep.length; n++) keep[n] = new int[256 * 256];
        } catch (OutOfMemoryError e) {}
        line("heap: " + n + " decoded 256x256 tiles fit (" + n * 256 / 1024 + " MB, " + (System.currentTimeMillis() - t) + " ms)");
        summary.append("heap: ").append(n).append(" tiles of 256x256 fit\n");
        keep = null;
        System.gc();
    }

    // ---- 2. files ----
    void files() {
        File[] roots = File.listRoots();
        StringBuffer s = new StringBuffer();
        for (int i = 0; roots != null && i < roots.length; i++) s.append(roots[i].getPath()).append(' ');
        line("== Files (java.io). Drives: " + s);
        byte[] data = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) data[i] = (byte) (i * 31 + 7);
        for (int i = 0; roots != null && i < roots.length; i++) {
            String root = roots[i].getPath();
            char c = Character.toUpperCase(root.charAt(0));
            if (c == 'Z' || c == 'A') continue;                     // ROM, nothing to write
            drive(c == 'C' ? new File(root, "Data" + File.separator + "PPProbe" + File.separator + "test")
                           : new File(root, "PPProbeTest"), c + ":", data);
        }
    }

    void drive(File dir, String name, byte[] data) {
        line("== " + name + " (" + dir.getPath() + ")");
        try {
            long t = System.currentTimeMillis();
            dir.mkdirs();
            line("make folder: " + p(System.currentTimeMillis() - t) + (dir.isDirectory() ? "" : " (NOT MADE)"));
            if (!dir.isDirectory()) { summary.append(name).append(" files: folder not made\n"); return; }
            long[] w = new long[N];
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < N; i++) {
                long a = System.currentTimeMillis();
                FileOutputStream o = new FileOutputStream(new File(dir, "t" + i + ".png"));
                o.write(data);
                o.close();
                w[i] = System.currentTimeMillis() - a;
            }
            long wAll = System.currentTimeMillis() - t0;
            line("write " + N + " x 20 KB: " + wAll + " ms, per file " + times(w) + " ms");
            t = System.currentTimeMillis();
            String[] list = dir.list();
            line("list folder: " + (list == null ? 0 : list.length) + " files, " + p(System.currentTimeMillis() - t));
            long[] r = new long[N];
            byte[] buf = new byte[SIZE];
            boolean same = true;
            t0 = System.currentTimeMillis();
            for (int i = 0; i < N; i++) {
                long a = System.currentTimeMillis();
                FileInputStream in = new FileInputStream(new File(dir, "t" + i + ".png"));
                int got = 0, k;
                while (got < SIZE && (k = in.read(buf, got, SIZE - got)) > 0) got += k;
                in.close();
                r[i] = System.currentTimeMillis() - a;
                if (got != SIZE || buf[i] != data[i]) same = false;
            }
            long rAll = System.currentTimeMillis() - t0;
            line("read " + N + " x 20 KB: " + rAll + " ms, per file " + times(r) + " ms" + (same ? "" : " (DATA DIFFERS!)"));
            t = System.currentTimeMillis();
            for (int i = 0; i < N; i++) new File(dir, "t" + i + ".png").delete();
            dir.delete();
            line("delete all: " + p(System.currentTimeMillis() - t));
            summary.append(name).append(" files: write ").append(wAll / N).append(" ms, read ").append(rAll / N).append(" ms per 20 KB tile\n");
        } catch (Throwable e) {
            line(name + ": " + e);
            summary.append(name).append(" files: ").append(e).append('\n');
        }
    }

    // ---- 3. network ----
    void network() {
        line("== Network");
        // Net Helper over a raw socket (what a PP Mapy would do: no HttpConnection limits)
        long[] raw = new long[6];
        boolean helper = true;
        for (int i = 0; i < raw.length; i++) {
            long a = System.currentTimeMillis();
            try {
                byte[] b = rawGet("127.0.0.1", 8123, "/fetch?u=" + enc(TOPO + (35803 + i) + "/22212.png"));
                raw[i] = System.currentTimeMillis() - a;
                if (i == 0) line("Net Helper, raw socket: first tile " + b.length + " B");
            } catch (Throwable e) {
                line("Net Helper, raw socket: " + e + (i == 0 ? " (is Net Helper running?)" : ""));
                helper = false;
                break;
            }
        }
        if (helper) {
            line("Net Helper, raw socket, 6 tiles: " + times(raw) + " ms");
            summary.append("tile through Net Helper (socket): ").append(avg(raw)).append(" ms\n");
            long[] u = new long[6];
            try {
                for (int i = 0; i < u.length; i++) {
                    long a = System.currentTimeMillis();
                    urlGet("http://127.0.0.1:8123/fetch?u=" + enc(TOPO + (35803 + i) + "/22213.png"));
                    u[i] = System.currentTimeMillis() - a;
                }
                line("Net Helper, java.net.URL, 6 tiles: " + times(u) + " ms");
                summary.append("tile through Net Helper (URL): ").append(avg(u)).append(" ms\n");
            } catch (Throwable e) { line("Net Helper, java.net.URL: " + e); }
            try {
                long a = System.currentTimeMillis();
                byte[] g = rawGet("127.0.0.1", 8124, "/gps");
                String s = new String(g, "ISO-8859-1");
                int nl = s.indexOf('\n');
                line("GPS from Net Helper: " + (System.currentTimeMillis() - a) + " ms, " + (nl > 0 ? s.substring(0, nl) : s));
            } catch (Throwable e) { line("GPS from Net Helper: " + e); }
        }
        // directly, http (the phone's own stack)
        long[] d = new long[6];
        try {
            for (int i = 0; i < d.length; i++) {
                long a = System.currentTimeMillis();
                urlGet(TOPO + (35803 + i) + "/22214.png");
                d[i] = System.currentTimeMillis() - a;
            }
            line("OpenTopoMap directly (http, URL), 6 tiles: " + times(d) + " ms");
            summary.append("tile directly (http): ").append(avg(d)).append(" ms\n");
        } catch (Throwable e) { line("OpenTopoMap directly: " + e); summary.append("direct http: ").append(e).append('\n'); }
        // directly, https: does PP have it at all?
        try {
            long a = System.currentTimeMillis();
            byte[] b = urlGet("https://tile.openstreetmap.org/16/35810/22215.png");
            line("OSM directly over https: " + b.length + " B, " + (System.currentTimeMillis() - a) + " ms");
            summary.append("https directly: works\n");
        } catch (Throwable e) { line("OSM directly over https: " + e); summary.append("https directly: ").append(e).append('\n'); }
    }

    /** One HTTP/1.0 GET over a plain socket; the body. */
    static byte[] rawGet(String host, int port, String path) throws IOException {
        Socket s = new Socket(host, port);
        try {
            s.setSoTimeout(30000);
            OutputStream o = s.getOutputStream();
            o.write(("GET " + path + " HTTP/1.0\r\nHost: " + host + "\r\nX-Ua: " + UA + "\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
            o.flush();
            byte[] all = readAll(s.getInputStream());
            int i = 0;
            while (i + 3 < all.length && !(all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n')) i++;
            String head = new String(all, 0, Math.min(i, all.length), "ISO-8859-1");
            if (!head.startsWith("HTTP/1.") || head.indexOf(" 200") < 0) {
                int nl = head.indexOf('\r');
                throw new IOException(nl > 0 ? head.substring(0, nl) : "no HTTP answer");
            }
            byte[] body = new byte[Math.max(0, all.length - i - 4)];
            System.arraycopy(all, i + 4, body, 0, body.length);
            return body;
        } finally {
            s.close();
        }
    }

    static byte[] urlGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            byte[] b = readAll(in);
            in.close();
            return b;
        } finally {
            c.disconnect();
        }
    }

    static String post(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            byte[] b = body.getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setRequestProperty("Content-Length", "" + b.length);
            OutputStream o = c.getOutputStream();
            o.write(b);
            o.close();
            return "HTTP " + c.getResponseCode() + ", " + b.length + " B";
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int k;
        while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
        return b.toByteArray();
    }

    static String enc(String s) {
        StringBuffer b = new StringBuffer();
        String hex = "0123456789ABCDEF";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') b.append(c);
            else b.append('%').append(hex.charAt((c >> 4) & 15)).append(hex.charAt(c & 15));
        }
        return b.toString();
    }

    // ---- 4. decoding and drawing ----
    void decode() {
        line("== Decoding one map tile 5 times");
        String via = "Net Helper";
        try {
            tile = rawGet("127.0.0.1", 8123, "/fetch?u=" + enc(TILE));
        } catch (Throwable e) {
            via = "directly";
            try { tile = urlGet(TILE); } catch (Throwable e2) { line("download failed: " + e2); return; }
        }
        line("tile " + tile.length + " B (" + via + ")");
        Toolkit tk = Toolkit.getDefaultToolkit();
        long[] d = new long[5];
        Image img = null;
        for (int i = 0; i < d.length; i++) {
            long a = System.currentTimeMillis();
            img = tk.createImage(tile);
            MediaTracker mt = new MediaTracker(this);
            mt.addImage(img, 0);
            try { mt.waitForID(0); } catch (InterruptedException e) {}
            d[i] = System.currentTimeMillis() - a;
            if (mt.isErrorID(0)) { line("decoding error"); return; }
        }
        line("decode: " + times(d) + " ms, size " + img.getWidth(null) + "x" + img.getHeight(null));
        summary.append("PP decode 256x256 PNG: ").append(avg(d)).append(" ms\n");
        // drawing: the tile 20 times into an off-screen picture the size of the screen
        try {
            Dimension s = getSize();
            Image off = createImage(Math.max(256, s.width), Math.max(256, s.height));
            Graphics g = off.getGraphics();
            long a = System.currentTimeMillis();
            for (int i = 0; i < 20; i++) g.drawImage(img, (i * 37) % Math.max(1, s.width - 256), 0, null);
            long dr = System.currentTimeMillis() - a;
            g.dispose();
            line("drawing the tile 20x off screen: " + dr + " ms (" + dr / 20 + " ms each)");
            summary.append("PP draw a tile: ").append(dr / 20).append(" ms\n");
        } catch (Throwable e) { line("drawing: " + e); }
    }

    void saveFile() throws IOException {
        File dir = new File("C:" + File.separator + "Data" + File.separator + "PPProbe");
        dir.mkdirs();
        FileOutputStream o = new FileOutputStream(new File(dir, "result.txt"));
        o.write(report.toString().getBytes("UTF-8"));
        o.close();
        line("saved C:\\Data\\PPProbe\\result.txt");
    }
}
