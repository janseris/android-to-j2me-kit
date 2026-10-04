package probe;

import java.util.Vector;
import javax.microedition.lcdui.*;

/** How much heap can the app get? byte[] blocks, then 256x256 mutable images, until OutOfMemoryError. */
class MemoryTest extends Thread {
    public void run() {
        Probe p = Probe.app;
        Form f = new Form("Paměť");
        f.append("Probíhá test...");
        p.show(f);
        p.log("--- memory test");
        p.memLine("before");
        Runtime r = Runtime.getRuntime();
        Vector v = new Vector();
        long t0 = System.currentTimeMillis();
        int blocks = 0;
        try {
            while (blocks < 400) {          // max 400 * 64 KB = 25 MB
                v.addElement(new byte[65536]);
                blocks++;
            }
        } catch (Throwable e) {
            p.log("byte[] OOM: " + e);
        }
        long maxTotal = r.totalMemory();
        p.log("byte[64K] blocks: " + blocks + " (" + (blocks * 64) + " KB) in "
            + (System.currentTimeMillis() - t0) + " ms, totalMemory then " + maxTotal);
        v.removeAllElements();
        System.gc();
        p.memLine("after gc");

        int images = 0;
        t0 = System.currentTimeMillis();
        try {
            while (images < 200) {
                Image im = Image.createImage(256, 256);
                Graphics g = im.getGraphics();
                g.setColor(images * 997);
                g.fillRect(0, 0, 256, 256);
                v.addElement(im);
                images++;
            }
        } catch (Throwable e) {
            p.log("Image OOM: " + e);
        }
        p.log("Image 256x256: " + images + " in " + (System.currentTimeMillis() - t0)
            + " ms, totalMemory then " + r.totalMemory());
        v.removeAllElements();
        v = null;
        System.gc();
        p.memLine("end");
        String res = "byte[] " + (blocks * 64) + " KB\nobrázky 256x256: " + images
            + "\nmax totalMemory: " + maxTotal + "\n\n(výsledek je v logu)";
        p.message("Paměť", res);
    }
}
