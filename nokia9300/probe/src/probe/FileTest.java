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
    static final int N = 30, SIZE = 20 * 1024;
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
        line("If the phone asks to allow file access, answer Yes and note how often it asks.");
        data = new byte[SIZE];
        Random rnd = new Random(9300);
        for (int i = 0; i < SIZE; i++) data[i] = (byte) rnd.nextInt();
        try {
            Vector roots = new Vector();
            long t = System.currentTimeMillis();
            for (Enumeration e = FileSystemRegistry.listRoots(); e.hasMoreElements();) roots.addElement(e.nextElement());
            line("Drives (" + (System.currentTimeMillis() - t) + " ms): " + roots);
            String[] props = { "fileconn.dir.photos", "fileconn.dir.private", "fileconn.dir.memorycard" };
            for (int i = 0; i < props.length; i++) {
                String v = System.getProperty(props[i]);
                if (v != null) line(props[i] + " = " + v);
            }
            for (int i = 0; i < roots.size() && running; i++) drive((String) roots.elementAt(i));
        } catch (Throwable e) {
            line("FileConnection failed: " + e);
        }
        if (running) recordStore();
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

    /** Tile-sized files on one drive: make a folder, write, list, read, delete; all timed. */
    void drive(String root) {
        String base = "file:///" + root;
        // C: has no room for user files at its top on Series 80: use C:/Data/ there
        String dir = base + (root.toUpperCase().startsWith("C") ? "Data/" : "") + "ProbeFileTest/";
        line("== " + root + " (" + dir + ")");
        FileConnection fc = null;
        try {
            long t = System.currentTimeMillis();
            fc = (FileConnection) Connector.open(base, Connector.READ);
            long open = System.currentTimeMillis() - t;
            String space = "";
            try { space = ", " + fc.availableSize() / 1024 + " of " + fc.totalSize() / 1024 + " KB free"; } catch (Throwable e) {}
            fc.close();
            fc = null;
            line("open drive: " + p(open) + space);

            t = System.currentTimeMillis();
            fc = (FileConnection) Connector.open(dir, Connector.READ_WRITE);
            if (!fc.exists()) fc.mkdir();
            fc.close();
            fc = null;
            line("make folder: " + p(System.currentTimeMillis() - t));

            long[] w = new long[N];
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < N && running; i++) {
                long a = System.currentTimeMillis();
                fc = (FileConnection) Connector.open(dir + "t" + i + ".png", Connector.READ_WRITE);
                if (!fc.exists()) fc.create();
                OutputStream o = fc.openOutputStream();
                o.write(data);
                o.close();
                fc.close();
                fc = null;
                w[i] = System.currentTimeMillis() - a;
            }
            long wAll = System.currentTimeMillis() - t0;
            line("write " + N + " x 20 KB: " + wAll + " ms, per file " + times(w) + " ms");

            t = System.currentTimeMillis();
            fc = (FileConnection) Connector.open(dir, Connector.READ);
            int n = 0;
            for (Enumeration e = fc.list(); e.hasMoreElements(); e.nextElement()) n++;
            fc.close();
            fc = null;
            line("list folder: " + n + " files, " + p(System.currentTimeMillis() - t));

            long[] r = new long[N];
            byte[] buf = new byte[SIZE];
            boolean same = true;
            t0 = System.currentTimeMillis();
            for (int i = 0; i < N && running; i++) {
                long a = System.currentTimeMillis();
                fc = (FileConnection) Connector.open(dir + "t" + i + ".png", Connector.READ);
                InputStream in = fc.openInputStream();
                int got = 0, k;
                while (got < SIZE && (k = in.read(buf, got, SIZE - got)) > 0) got += k;
                in.close();
                fc.close();
                fc = null;
                r[i] = System.currentTimeMillis() - a;
                if (got != SIZE || buf[i] != data[i]) same = false;
            }
            long rAll = System.currentTimeMillis() - t0;
            line("read " + N + " x 20 KB: " + rAll + " ms, per file " + times(r) + " ms" + (same ? "" : " (DATA DIFFERS!)"));

            t = System.currentTimeMillis();
            for (int i = 0; i < N; i++) {
                fc = (FileConnection) Connector.open(dir + "t" + i + ".png", Connector.READ_WRITE);
                if (fc.exists()) fc.delete();
                fc.close();
                fc = null;
            }
            fc = (FileConnection) Connector.open(dir, Connector.READ_WRITE);
            fc.delete();
            fc.close();
            fc = null;
            line("delete all: " + p(System.currentTimeMillis() - t));
            summary.append(root).append(" files: write ").append(wAll / N).append(" ms, read ").append(rAll / N).append(" ms per 20 KB tile\n");
        } catch (Throwable e) {
            line(root + ": " + e);
            summary.append(root).append(" files: ").append(e).append('\n');
        } finally {
            try { if (fc != null) fc.close(); } catch (Throwable e) {}
        }
    }

    /** The same into a new, empty record store (Mapy's old cache; its 8.5 MB one was far slower). */
    void recordStore() {
        line("== Record store (new and empty)");
        String name = "probefiletest";
        try { RecordStore.deleteRecordStore(name); } catch (Throwable e) {}
        try {
            RecordStore rs = RecordStore.openRecordStore(name, true);
            long[] w = new long[N];
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < N && running; i++) {
                long a = System.currentTimeMillis();
                rs.addRecord(data, 0, SIZE);
                w[i] = System.currentTimeMillis() - a;
            }
            long wAll = System.currentTimeMillis() - t0;
            line("write " + N + " x 20 KB: " + wAll + " ms, per record " + times(w) + " ms");
            long[] r = new long[N];
            t0 = System.currentTimeMillis();
            for (int i = 0; i < N && running; i++) {
                long a = System.currentTimeMillis();
                rs.getRecord(i + 1);
                r[i] = System.currentTimeMillis() - a;
            }
            long rAll = System.currentTimeMillis() - t0;
            line("read " + N + " x 20 KB: " + rAll + " ms, per record " + times(r) + " ms");
            rs.closeRecordStore();
            RecordStore.deleteRecordStore(name);
            summary.append("record store: write ").append(wAll / N).append(" ms, read ").append(rAll / N).append(" ms per 20 KB tile\n");
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
