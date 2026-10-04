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
    String tileUrl = "https://api.mapy.com/v1/maptiles/outdoor/256/{z}/{x}/{y}?apikey={key}";
    String apiKey = "";
    /** OSM's tile policy requires a User-Agent that names the app (no browser or library default). */
    String userAgent = "Probe9300/1.1 (+https://github.com/janseris/android-to-j2me-kit)";
    static final String URL_MAPY = "https://api.mapy.com/v1/maptiles/outdoor/256/{z}/{x}/{y}?apikey={key}";
    static final String URL_OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    String btAddress = "";

    static final Command BACK = new Command("Zpět", Command.BACK, 1);
    static final Command EXIT = new Command("Konec", Command.EXIT, 9);
    static final Command OK = new Command("OK", Command.OK, 1);

    public Probe() { app = this; }

    protected void startApp() {
        if (display != null) return;
        display = Display.getDisplay(this);
        load();
        menu = new List("Nokia 9300 probe", List.IMPLICIT);
        menu.append("Paměť (heap, obrázky)", null);
        menu.append("Bluetooth GPS", null);
        menu.append("Mapové dlaždice", null);
        menu.append("Nastavení (PC, URL, klíč, User-Agent)", null);
        menu.append("Test hlaviček (co telefon posílá)", null);
        menu.append("Log", null);
        menu.append("Odeslat log na PC", null);
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
            switch (menu.getSelectedIndex()) {
                case 0: new MemoryTest().start(); break;
                case 1: show(new BtGpsScreen()); break;
                case 2: show(new TileScreen()); break;
                case 3: settings(); break;
                case 4: headersTest(); break;
                case 5: showLog(); break;
                case 6: sendLog(); break;
            }
        }
    }

    // ---- log ----
    synchronized void log(String s) {
        log.append(s).append('\n');
        if (log.length() > 30000) log.delete(0, log.length() - 24000);
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
        final TextField tBt = new TextField("BT adresa GPS (12 hex, prázdné = hledat)", btAddress, 12, TextField.ANY);
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
                try { userAgent = in.readUTF(); } catch (EOFException e) {}
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
