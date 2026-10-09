package probe;

import java.io.*;
import java.util.Enumeration;
import java.util.Random;
import java.util.Vector;
import javax.microedition.io.*;
import javax.microedition.io.file.*;
import javax.microedition.lcdui.*;
import javax.microedition.rms.RecordStore;

/**
 * Files from Java (JSR-75 FileConnection) against the record store: could Mapy keep its own tile
 * cache in plain files when Net Helper isn't running? Writes and reads 30 tile-sized (20 KB) files
 * on every drive it may, the same into a new record store, and times all of it. Probe is unsigned,
 * so the phone may ask "Allow?" for files: a step that waited for an answer shows as a long time
 * (marked "(prompt?)"). At the end: decoding one real map tile 5 times, to compare with the Java
 * Personal Profile test (PP Probe), which decodes the same tile. Everything it writes is deleted.
 * Results go to the PC (uploads/..._filetest.txt).
 */
public class FileTest extends Canvas implements CommandListener, Runnable {
    static final Command STOP = new Command("Back", Command.BACK, 1);
    static final int N = 5, SIZE = 20 * 1024;
    /** The tile PP Probe decodes too (Brno, zoom 16). */
    static final String TILE = "https://tile.openstreetmap.org/16/35803/22210.png";

    final Vector lines = new Vector();
    final StringBuffer report = new StringBuffer();
    final StringBuffer summary = new StringBuffer();
    volatile boolean running = true;
    byte[] data;

    FileTest() {
        setTitle("Files (FileConnection) vs record store");
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

    static String p(long ms) { return ms + " ms" + (ms > 1500 ? " (prompt?)" : ""); }

    public void run() {
        line("== File test, " + System.getProperty("microedition.platform") + ", FileConnection "
            + System.getProperty("microedition.io.file.FileConnection.version"));
        line("The record store first (no prompts), then files: if the phone asks to allow file access, answer Yes and note how often it asks.");
        data = new byte[SIZE];
        Random rnd = new Random(9300);
        for (int i = 0; i < SIZE; i++) data[i] = (byte) rnd.nextInt();
        // the record store first: no prompts, the numbers are real
        recordStore();
        try {
            String priv = System.getProperty("fileconn.dir.private");
            if (priv != null && running) drive("Probe's own folder", priv + "ProbeFileTest/");
            String card = System.getProperty("fileconn.dir.memorycard");
            if (running) drive("Memory card", (card != null ? card : "file:///D:/") + "ProbeFileTest/");
        } catch (Throwable e) {
            line("FileConnection failed: " + e);
        }
        if (running) decode();
        line("== Summary");
        line(summary.toString().trim());
        try {
            line(Probe.post("http://" + Probe.app.pc + "/results?name=filetest", report.toString()));
        } catch (Throwable e) {
            line("(sending to PC failed: " + e + ")");
        }
        line("Done. Back = menu.");
    }

    /**
     * Tile-sized files in one folder. Each step is timed in two parts: opening the file (where the
     * phone asks "Allow?", so that part includes your answer) and moving the data (no prompts):
     * the data part is the real speed.
     */
    void drive(String label, String dir) {
        line("== " + label + " (" + dir + ")");
        FileConnection fc = null;
        try {
            long t = System.currentTimeMillis();
            fc = (FileConnection) Connector.open(dir, Connector.READ_WRITE);
            if (!fc.exists()) fc.mkdir();
            String space = "";
            try { space = ", " + fc.availableSize() / 1024 + " KB free"; } catch (Throwable e) {}
            fc.close();
            fc = null;
            line("folder: " + p(System.currentTimeMillis() - t) + space);

            long[] wo = new long[N], wd = new long[N];
            for (int i = 0; i < N && running; i++) {
                long a = System.currentTimeMillis();
                fc = (FileConnection) Connector.open(dir + "t" + i + ".png", Connector.READ_WRITE);
                if (!fc.exists()) fc.create();
                OutputStream o = fc.openOutputStream();
                long b = System.currentTimeMillis();
                o.write(data);
                o.close();
                fc.close();
                fc = null;
                wd[i] = System.currentTimeMillis() - b;
                wo[i] = b - a;
            }
            line("write " + N + " x 20 KB: data " + times(wd) + " ms; opening (incl. prompts) " + times(wo) + " ms");

            long[] ro = new long[N], rd = new long[N];
            byte[] buf = new byte[SIZE];
            boolean same = true;
            for (int i = 0; i < N && running; i++) {
                long a = System.currentTimeMillis();
                fc = (FileConnection) Connector.open(dir + "t" + i + ".png", Connector.READ);
                InputStream in = fc.openInputStream();
                long b = System.currentTimeMillis();
                int got = 0, k;
                while (got < SIZE && (k = in.read(buf, got, SIZE - got)) > 0) got += k;
                in.close();
                fc.close();
                fc = null;
                rd[i] = System.currentTimeMillis() - b;
                ro[i] = b - a;
                if (got != SIZE || buf[i] != data[i]) same = false;
            }
            line("read " + N + " x 20 KB: data " + times(rd) + " ms; opening (incl. prompts) " + times(ro) + " ms" + (same ? "" : " (DATA DIFFERS!)"));

            // one file, many tiles: opened (and allowed) once, 10 tiles written and read through it
            long a = System.currentTimeMillis();
            fc = (FileConnection) Connector.open(dir + "big.bin", Connector.READ_WRITE);
            if (!fc.exists()) fc.create();
            OutputStream o = fc.openOutputStream();
            long b = System.currentTimeMillis();
            for (int i = 0; i < 10; i++) o.write(data);
            o.close();
            fc.close();
            fc = null;
            long bigW = System.currentTimeMillis() - b;
            fc = (FileConnection) Connector.open(dir + "big.bin", Connector.READ);
            InputStream in = fc.openInputStream();
            long c = System.currentTimeMillis();
            int got = 0, k;
            byte[] big = new byte[10 * SIZE];
            while (got < big.length && (k = in.read(big, got, big.length - got)) > 0) got += k;
            in.close();
            fc.close();
            fc = null;
            long bigR = System.currentTimeMillis() - c;
            line("one file of 10 tiles (200 KB): write " + bigW + " ms, read " + bigR + " ms (opening " + (b - a) + " ms)");

            t = System.currentTimeMillis();
            for (int i = 0; i < N; i++) {
                fc = (FileConnection) Connector.open(dir + "t" + i + ".png", Connector.READ_WRITE);
                if (fc.exists()) fc.delete();
                fc.close();
                fc = null;
            }
            fc = (FileConnection) Connector.open(dir + "big.bin", Connector.READ_WRITE);
            if (fc.exists()) fc.delete();
            fc.close();
            fc = (FileConnection) Connector.open(dir, Connector.READ_WRITE);
            fc.delete();
            fc.close();
            fc = null;
            line("delete all: " + p(System.currentTimeMillis() - t));
            summary.append(label).append(": per 20 KB tile write ").append(avg(wd)).append(" ms, read ").append(avg(rd))
                .append(" ms (data only); 10 tiles in one file: write ").append(bigW).append(" ms, read ").append(bigR).append(" ms\n");
        } catch (Throwable e) {
            line(label + ": " + e);
            summary.append(label).append(": ").append(e).append('\n');
        } finally {
            try { if (fc != null) fc.close(); } catch (Throwable e) {}
        }
    }

    static long avg(long[] a) {
        long s = 0;
        for (int i = 0; i < a.length; i++) s += a[i];
        return a.length == 0 ? 0 : s / a.length;
    }

    /**
     * The record store: no prompts there, so these are real. Does a write cost the same whatever
     * its size (then several tiles in one record would be much faster), or does it grow with it?
     */
    void recordStore() {
        line("== Record store (new and empty, no prompts)");
        String name = "probefiletest";
        try { RecordStore.deleteRecordStore(name); } catch (Throwable e) {}
        try {
            RecordStore rs = RecordStore.openRecordStore(name, true);
            int[] sizes = { 100, 1024, 20 * 1024, 100 * 1024, 200 * 1024 };
            for (int s = 0; s < sizes.length && running; s++) {
                byte[] b = new byte[sizes[s]];
                for (int i = 0; i < b.length; i++) b[i] = data[i % SIZE];
                long[] w = new long[4];
                for (int i = 0; i < w.length && running; i++) {
                    long a = System.currentTimeMillis();
                    rs.addRecord(b, 0, b.length);
                    w[i] = System.currentTimeMillis() - a;
                }
                // changing a record that exists (Mapy's way when a tile is fetched again)
                long a = System.currentTimeMillis();
                rs.setRecord(rs.getNextRecordID() - 1, b, 0, b.length);
                long set = System.currentTimeMillis() - a;
                a = System.currentTimeMillis();
                rs.getRecord(rs.getNextRecordID() - 1);
                long get = System.currentTimeMillis() - a;
                String kb = sizes[s] < 1024 ? sizes[s] + " B" : sizes[s] / 1024 + " KB";
                line(kb + ": add " + times(w) + " ms, change " + set + " ms, read " + get + " ms");
                summary.append("record store ").append(kb).append(": write ").append(avg(w)).append(" ms, read ").append(get).append(" ms\n");
            }
            line("store now " + rs.getSize() / 1024 + " KB, " + rs.getNumRecords() + " records");
            rs.closeRecordStore();
            RecordStore.deleteRecordStore(name);
        } catch (Throwable e) {
            line("record store: " + e);
        }
    }

    /** One real tile decoded 5 times (PP Probe does the same tile, for comparison). */
    void decode() {
        line("== Decoding one map tile 5 times");
        byte[] png = null;
        String via = "Net Helper";
        try {
            png = get("http://127.0.0.1:8123/fetch?u=" + HelperFetch.encode(TILE));
        } catch (Throwable e) {
            via = "direct";
            try { png = get(TILE); } catch (Throwable e2) { line("download failed: " + e2); return; }
        }
        line("tile " + png.length + " B (" + via + ")");
        long[] d = new long[5];
        for (int i = 0; i < 5 && running; i++) {
            long a = System.currentTimeMillis();
            Image img = Image.createImage(png, 0, png.length);
            d[i] = System.currentTimeMillis() - a;
            if (img.getWidth() != 256) line("size " + img.getWidth());
        }
        line("decode: " + times(d) + " ms");
        long sum = 0;
        for (int i = 1; i < 5; i++) sum += d[i];
        summary.append("MIDP decode 256x256 PNG: ").append(sum / 4).append(" ms\n");
    }

    byte[] get(String url) throws IOException {
        HttpConnection c = (HttpConnection) Connector.open(url);
        InputStream in = null;
        try {
            c.setRequestProperty("User-Agent", Probe.app.userAgent);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            in = c.openInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int k;
            while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
            return b.toByteArray();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable e) {}
            c.close();
        }
    }

    static String times(long[] a) {
        StringBuffer s = new StringBuffer("[");
        for (int i = 0; i < a.length; i++) s.append(i > 0 ? " " : "").append(a[i]);
        return s.append(']').toString();
    }

    public void commandAction(Command c, Displayable d) {
        running = false;
        Probe.app.back();
    }

    boolean full;

    protected void keyPressed(int key) {
        if (key == 'f' || key == 'F') {
            full = !full;
            setFullScreenMode(full);
            repaint();
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setColor(0x1E1F22);
        g.fillRect(0, 0, w, h);
        g.setFont(f);
        int fh = f.getHeight(), rows = h / fh;
        Vector shown = new Vector(), colors = new Vector();
        synchronized (lines) {
            for (int i = lines.size() - 1; i >= 0 && shown.size() < rows; i--) {
                String s = (String) lines.elementAt(i);
                Vector part = TileSpeed.wrap(f, s, w - 6);
                Integer c = new Integer(s.startsWith("==") ? 0xFCEE74 : 0xDBDEE1);
                for (int k = part.size() - 1; k >= 0 && shown.size() < rows; k--) {
                    shown.insertElementAt(part.elementAt(k), 0);
                    colors.insertElementAt(c, 0);
                }
            }
        }
        for (int i = 0; i < shown.size(); i++) {
            g.setColor(((Integer) colors.elementAt(i)).intValue());
            g.drawString((String) shown.elementAt(i), 3, i * fh, Graphics.TOP | Graphics.LEFT);
        }
    }
}
