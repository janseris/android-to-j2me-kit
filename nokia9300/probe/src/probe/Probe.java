package probe;

import java.io.*;
import java.util.*;
import javax.microedition.io.*;
import javax.microedition.lcdui.*;
import javax.microedition.midlet.*;
import javax.microedition.rms.*;

/**
 * Device probe for the Nokia 9300: heap limits, Bluetooth GPS (NMEA over SPP), and map tile
 * download/decode. Every result goes to a log that can be sent to the PC (ota_server.js).
 */
public class Probe extends MIDlet implements CommandListener {
    static Probe app;
    Display display;
    List menu;
    final StringBuffer log = new StringBuffer();
    String pc = "192.168.137.1:8000";
    String tileUrl = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    String apiKey = "";
    /** OSM's tile policy requires a User-Agent that names the app (no browser or library default). */
    String userAgent = "Probe9300/3.8 (J2ME device test; Nokia 9300; SymbianOS/7.0s Series80/2.0; Profile/MIDP-2.0 Configuration/CLDC-1.1)";
    static final String URL_MAPY = "https://api.mapy.com/v1/maptiles/outdoor/256/{z}/{x}/{y}?apikey={key}";
    static final String URL_OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    String btAddress = "";

    static final Command BACK = new Command("Zpět", Command.BACK, 1);
    static final Command EXIT = new Command("Konec", Command.EXIT, 9);
    static final Command OK = new Command("Uložit", Command.SCREEN, 1);   // SCREEN: shown on a side button on the 9300, OK went to the menu

    static final String T_FILES = "Files (FileConnection) vs record store + decoding",
        T_COMPARE = "Speed: alone / with GPS / GPS + decoding (Net Helper)",
        T_TILE_HGPS = "Map servers + GPS from Net Helper (like Mapy 4.15)",
        T_TILE_MAPY = "Map servers like Mapy (GPS in background + decoding)",
        T_TILE_SPEED = "Map servers: which is fastest (direct / Net Helper)",
        T_HELPER_FETCH = "Net Helper: dlaždice přímo vs přes helper",
        T_HELPER = "Test Net Helper (http://127.0.0.1:8123)",
        T_KEEPALIVE = "Keep-alive test (socket:// a ssl://, jedno spojení)",
        T_BIG = "VELKÝ TEST (síť http/https, obrázky, úložiště, socket)",
        T_RAW = "Test hlaviček: surové bajty (port +1)",
        T_CONN = "Test spojení (cena HTTPS, loopback)",
        T_HEADERS = "Test hlaviček (co telefon posílá)",
        T_BT = "Bluetooth GPS",
        T_TILES = "Mapové dlaždice",
        T_MEMORY = "Paměť (heap, obrázky)",
        T_SETTINGS = "Nastavení (PC, URL, klíč, User-Agent)",
        T_LOG = "Log",
        T_SEND = "Odeslat log na PC";
    /** Menu: the newest test first (add new ones at the top). */
    static final String[] ITEMS = { T_FILES, T_COMPARE, T_TILE_HGPS, T_TILE_MAPY, T_TILE_SPEED, T_HELPER_FETCH, T_HELPER, T_KEEPALIVE, T_BIG, T_RAW, T_CONN, T_HEADERS, T_BT, T_TILES,
        T_MEMORY, T_SETTINGS, T_LOG, T_SEND };

    public Probe() { app = this; }

    protected void startApp() {
        if (display != null) return;
        display = Display.getDisplay(this);
        load();
        loadPreviousLog();
        // the log survives crashes: saved to RMS every 5 s, sent together with the previous run's log
        new Thread() {
            public void run() {
                int saved = 0;
                while (true) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) {}
                    int len;
                    synchronized (Probe.this) { len = log.length(); }
                    if (len != saved) { saveLog(); saved = len; }
                }
            }
        }.start();
        menu = new List("Nokia 9300 probe", List.IMPLICIT);
        // newest tests on top, then the older ones; the log at the bottom
        for (int i = 0; i < ITEMS.length; i++) menu.append(ITEMS[i], null);
        menu.addCommand(EXIT);
        menu.setCommandListener(this);
        log("probe start: " + System.getProperty("microedition.platform")
            + ", CLDC " + System.getProperty("microedition.configuration")
            + ", bluetooth.api.version=" + System.getProperty("bluetooth.api.version")
            + ", file.version=" + System.getProperty("microedition.io.file.FileConnection.version"));
        memLine("start");
        show(menu);
    }

    protected void pauseApp() {}
    protected void destroyApp(boolean u) {}

    void show(Displayable d) { display.setCurrent(d); }
    void back() { show(menu); }

    public void commandAction(Command c, Displayable d) {
        if (c == EXIT) { notifyDestroyed(); return; }
        if (d == menu) {
            int i = menu.getSelectedIndex();
            String item = i >= 0 ? ITEMS[i] : "";
            if (item == T_FILES) show(new FileTest());
            else if (item == T_COMPARE) show(new TileSpeed(3));
            else if (item == T_TILE_HGPS) show(new TileSpeed(2));
            else if (item == T_TILE_MAPY) show(new TileSpeed(1));
            else if (item == T_TILE_SPEED) show(new TileSpeed());
            else if (item == T_HELPER_FETCH) show(new HelperFetch());
            else if (item == T_HELPER) helperTest();
            else if (item == T_KEEPALIVE) show(new KeepAlive());
            else if (item == T_BIG) show(new BigTest());
            else if (item == T_RAW) rawHeadersTest();
            else if (item == T_CONN) show(new ConnTest());
            else if (item == T_HEADERS) headersTest();
            else if (item == T_BT) show(new BtGpsScreen());
            else if (item == T_TILES) show(new TileScreen());
            else if (item == T_MEMORY) new MemoryTest().start();
            else if (item == T_SETTINGS) settings();
            else if (item == T_LOG) showLog();
            else if (item == T_SEND) sendLog();
        }
    }

    // ---- log ----
    synchronized void log(String s) {
        log.append(s).append('\n');
        if (log.length() > 30000) log.delete(0, log.length() - 24000);
    }

    synchronized String logText() { return log.toString(); }

    String previousLog = "";

    void loadPreviousLog() {
        try {
            RecordStore rs = RecordStore.openRecordStore("probelog", true);
            if (rs.getNumRecords() > 0) {
                byte[] b = rs.getRecord(1);
                previousLog = new String(b, "UTF-8");
            }
            rs.closeRecordStore();
        } catch (Throwable e) {}
    }

    synchronized void saveLog() {
        try {
            byte[] b = log.toString().getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("probelog", true);
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, b.length);
            else rs.setRecord(1, b, 0, b.length);
            rs.closeRecordStore();
        } catch (Throwable e) {}
    }

    void memLine(String label) {
        Runtime r = Runtime.getRuntime();
        log("mem " + label + ": total " + r.totalMemory() + " free " + r.freeMemory());
    }

    void showLog() {
        Form f = new Form("Log");
        String s;
        synchronized (this) { s = log.toString(); }
        if (s.length() > 6000) s = "...\n" + s.substring(s.length() - 6000);
        f.append(s);
        f.addCommand(BACK);
        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) { back(); }
        });
        show(f);
    }

    void message(String title, String text) {
        Alert a = new Alert(title, text, null, AlertType.INFO);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a, menu);
    }

    void sendLog() {
        new Thread() {
            public void run() {
                String s;
                synchronized (Probe.this) { s = log.toString(); }
                if (previousLog.length() > 0) s = "===== PŘEDCHOZÍ BĚH (uložený log, např. před pádem) =====\n" + previousLog
                    + "\n===== TENTO BĚH =====\n" + s;
                try {
                    String r = post("http://" + pc + "/results?name=probe", s);
                    message("Odesláno", r);
                } catch (Throwable e) {
                    message("Chyba", e.toString());
                }
            }
        }.start();
    }

    /** GET http://PC/headers with our User-Agent; ota_server.js echoes the headers it received. */
    void headersTest() {
        new Thread() {
            public void run() {
                HttpConnection c = null;
                InputStream in = null;
                try {
                    c = (HttpConnection) Connector.open("http://" + pc + "/headers");
                    c.setRequestProperty("User-Agent", userAgent);
                    int code = c.getResponseCode();
                    in = c.openInputStream();
                    byte[] b = TileScreen.readAll(in, (int) c.getLength());
                    String text = new String(b, "UTF-8");
                    log("--- headers test: HTTP " + code + ", server received:\n" + text);
                    message("Hlavičky", text);
                } catch (Throwable e) {
                    log("headers test failed: " + e);
                    message("Chyba", e.toString());
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable e) {}
                    try { if (c != null) c.close(); } catch (Throwable e) {}
                }
            }
        }.start();
    }

    /**
     * The exact bytes the phone's HTTP stack sends, from ota_server.js' raw echo (port + 1), with a
     * short and two long User-Agents: ČÚZK's IIS answered Mapy with "invalid header name".
     */
    void rawHeadersTest() {
        new Thread() {
            public void run() {
                int colon = pc.indexOf(':');
                String host = colon < 0 ? pc : pc.substring(0, colon);
                int port = colon < 0 ? 80 : Integer.parseInt(pc.substring(colon + 1));
                String[] uas = { "Probe9300/3.8", userAgent,
                    "Mapy9300/3.6 (J2ME map app; Nokia 9300; SymbianOS/7.0s Series80/2.0; Profile/MIDP-2.0 Configuration/CLDC-1.1)" };
                StringBuffer all = new StringBuffer();
                for (int i = 0; i < uas.length; i++) {
                    HttpConnection c = null;
                    InputStream in = null;
                    try {
                        c = (HttpConnection) Connector.open("http://" + host + ":" + (port + 1) + "/raw" + (i + 1));
                        c.setRequestProperty("User-Agent", uas[i]);
                        int code = c.getResponseCode();
                        in = c.openInputStream();
                        String text = new String(TileScreen.readAll(in, (int) c.getLength()), "ISO-8859-1");
                        all.append("--- UA ").append(uas[i].length()).append(" chars: HTTP ").append(code).append('\n').append(text).append('\n');
                    } catch (Throwable e) {
                        all.append("--- UA ").append(uas[i].length()).append(" chars: ").append(e).append('\n');
                    } finally {
                        try { if (in != null) in.close(); } catch (Throwable e) {}
                        try { if (c != null) c.close(); } catch (Throwable e) {}
                    }
                    try { Thread.sleep(300); } catch (InterruptedException e) {}
                }
                log("--- raw headers test:\n" + all);
                message("Surové hlavičky", all.toString());
            }
        }.start();
    }

    /** Asks the native helper (Net Helper 9300, a C++ app on this phone) on 127.0.0.1:8123, 3 times. */
    void helperTest() {
        new Thread() {
            public void run() {
                StringBuffer all = new StringBuffer();
                for (int i = 0; i < 3; i++) {
                    HttpConnection c = null;
                    InputStream in = null;
                    long t0 = System.currentTimeMillis();
                    try {
                        c = (HttpConnection) Connector.open("http://127.0.0.1:8123/test" + (i + 1));
                        c.setRequestProperty("User-Agent", userAgent);
                        int code = c.getResponseCode();
                        in = c.openInputStream();
                        String text = new String(TileScreen.readAll(in, (int) c.getLength()), "UTF-8");
                        all.append(i + 1).append(": HTTP ").append(code).append(", ").append(System.currentTimeMillis() - t0).append(" ms: ").append(text.trim()).append('\n');
                    } catch (Throwable e) {
                        all.append(i + 1).append(": ").append(e).append(" (").append(System.currentTimeMillis() - t0).append(" ms)\n");
                    } finally {
                        try { if (in != null) in.close(); } catch (Throwable e) {}
                        try { if (c != null) c.close(); } catch (Throwable e) {}
                    }
                    try { Thread.sleep(300); } catch (InterruptedException e) {}
                }
                log("--- helper test:\n" + all);
                saveLog();
                try { post("http://" + pc + "/results?name=nethelper", "Net Helper test\n" + all); } catch (Throwable e) { all.append("(sending to PC failed: " + e + ")"); }
                message("Net Helper", all.toString());
            }
        }.start();
    }

    static String post(String url, String body) throws IOException {
        HttpConnection c = (HttpConnection) Connector.open(url);
        try {
            byte[] b = body.getBytes("UTF-8");
            c.setRequestMethod(HttpConnection.POST);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setRequestProperty("Content-Length", "" + b.length);
            OutputStream o = c.openOutputStream();
            o.write(b);
            o.close();
            return "HTTP " + c.getResponseCode() + ", " + b.length + " B";
        } finally {
            c.close();
        }
    }

    // ---- settings (RMS) ----
    void settings() {
        final Form f = new Form("Nastavení");
        final TextField tPc = new TextField("PC (adresa:port)", pc, 64, TextField.ANY);
        final TextField tUrl = new TextField("URL dlaždic ({z} {x} {y} {key})", tileUrl, 300, TextField.ANY);
        final TextField tKey = new TextField("API klíč", apiKey, 128, TextField.ANY);
        final TextField tBt = new TextField("BT adresa GPS (např. 00:11:22:AA:BB:CC)", btAddress, 17, TextField.ANY);
        final TextField tUa = new TextField("User-Agent", userAgent, 200, TextField.ANY);
        final ChoiceGroup preset = new ChoiceGroup("Předvolba URL", Choice.EXCLUSIVE,
            new String[] { "ponechat", "Mapy.com (API klíč)", "OpenStreetMap" }, null);
        f.append(tPc); f.append(preset); f.append(tUrl); f.append(tKey); f.append(tUa); f.append(tBt);
        f.addCommand(OK); f.addCommand(BACK);
        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == OK) {
                    pc = tPc.getString().trim();
                    tileUrl = tUrl.getString().trim();
                    if (preset.getSelectedIndex() == 1) tileUrl = URL_MAPY;
                    if (preset.getSelectedIndex() == 2) tileUrl = URL_OSM;
                    userAgent = tUa.getString().trim();
                    apiKey = tKey.getString().trim();
                    btAddress = tBt.getString().trim();
                    save();
                }
                back();
            }
        });
        show(f);
    }

    void load() {
        try {
            RecordStore rs = RecordStore.openRecordStore("probe", true);
            if (rs.getNumRecords() > 0) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
                pc = in.readUTF(); tileUrl = in.readUTF(); apiKey = in.readUTF(); btAddress = in.readUTF();
                String defUa = userAgent;
                try { userAgent = in.readUTF(); } catch (EOFException e) {}
                if (userAgent.startsWith("Probe9300/")) userAgent = defUa;     // an older version's default
                // a Mapy.com URL without a key only gives HTTP 401: use OSM instead
                if (tileUrl.indexOf("{key}") >= 0 && apiKey.length() == 0) tileUrl = URL_OSM;
            }
            rs.closeRecordStore();
        } catch (Throwable e) {}
    }

    void save() {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeUTF(pc); o.writeUTF(tileUrl); o.writeUTF(apiKey); o.writeUTF(btAddress); o.writeUTF(userAgent);
            byte[] b = bo.toByteArray();
            RecordStore rs = RecordStore.openRecordStore("probe", true);
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, b.length);
            else rs.setRecord(1, b, 0, b.length);
            rs.closeRecordStore();
        } catch (Throwable e) {
            log("settings save failed: " + e);
        }
    }
}
