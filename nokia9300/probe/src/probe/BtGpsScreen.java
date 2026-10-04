package probe;

import java.io.*;
import java.util.Vector;
import javax.bluetooth.*;
import javax.microedition.io.*;
import javax.microedition.lcdui.*;

/**
 * Bluetooth GPS over the Serial Port Profile: find the device (or use the saved address),
 * find its SPP service, read NMEA and show the position. Works with a Bluetooth GPS receiver
 * or an Android phone running an NMEA-over-Bluetooth app (Share GPS, Bluetooth GPS Output, ...).
 */
class BtGpsScreen extends Form implements CommandListener, DiscoveryListener, Runnable {
    static final Command SEARCH = new Command("Hledat zařízení", Command.SCREEN, 1);
    static final Command CONNECT = new Command("Připojit (uložená adresa)", Command.SCREEN, 2);
    static final Command STOP = new Command("Odpojit", Command.STOP, 3);
    static final Command KNOWN = new Command("Spárovaná zařízení (bez hledání)", Command.SCREEN, 1);
    static final UUID SPP = new UUID(0x1101);

    final Probe p = Probe.app;
    final StringItem status = new StringItem("Stav", "");
    final StringItem pos = new StringItem("Poloha", "-");
    final StringItem raw = new StringItem("Poslední věta", "-");
    final StringItem stats = new StringItem("Statistika", "-");

    DiscoveryAgent agent;
    final Vector devices = new Vector();
    List deviceList;
    String serviceUrl;
    volatile boolean running;
    StreamConnection conn;

    BtGpsScreen() {
        super("Bluetooth GPS");
        append(status); append(pos); append(stats); append(raw);
        addCommand(KNOWN); addCommand(SEARCH); addCommand(CONNECT); addCommand(STOP); addCommand(Probe.BACK);
        setCommandListener(this);
        try {
            LocalDevice ld = LocalDevice.getLocalDevice();
            agent = ld.getDiscoveryAgent();
            String s = "JSR-82 OK, telefon " + ld.getBluetoothAddress() + " \"" + ld.getFriendlyName() + "\"";
            setStatus(s);
            p.log("--- bt: " + s + ", api " + LocalDevice.getProperty("bluetooth.api.version")
                + ", max connections " + LocalDevice.getProperty("bluetooth.connected.devices.max"));
        } catch (Throwable e) {
            setStatus("Bluetooth nedostupný: " + e);
            p.log("--- bt: unavailable: " + e);
        }
    }

    void setStatus(String s) { status.setText(s); }

    public void commandAction(Command c, Displayable d) {
        if (d == deviceList) {
            if (c == List.SELECT_COMMAND) {
                int i = deviceList.getSelectedIndex();
                if (i >= 0 && i < devices.size()) searchService((RemoteDevice) devices.elementAt(i));
            }
            p.show(this);
            return;
        }
        if (c == Probe.BACK) { stop(); p.back(); }
        else if (c == STOP) stop();
        else if (c == SEARCH) inquiry();
        else if (c == KNOWN) known();
        else if (c == CONNECT) {
            String a = cleanAddress(p.btAddress);
            if (a.length() != 12) { setStatus("Zadej BT adresu Androidu v Nastavení (Android: Nastavení > O telefonu > Stav > Adresa Bluetooth)."); return; }
            p.btAddress = a;
            // no RemoteDevice object without inquiry: make one from the address (protected constructor)
            RemoteDevice rd = new RemoteDevice(a) {};
            scanChannels = true;
            searchService(rd);
        }
    }

    /** Paired (PREKNOWN) and recently seen (CACHED) devices, without an inquiry: works while other links are open. */
    void known() {
        if (agent == null) return;
        devices.removeAllElements();
        int pre = 0, cached = 0;
        try {
            RemoteDevice[] k = agent.retrieveDevices(DiscoveryAgent.PREKNOWN);
            if (k != null) { pre = k.length; for (int i = 0; i < k.length; i++) deviceDiscovered(k[i], null); }
            k = agent.retrieveDevices(DiscoveryAgent.CACHED);
            if (k != null) { cached = k.length; for (int i = 0; i < k.length; i++) deviceDiscovered(k[i], null); }
        } catch (Throwable e) {
            p.log("bt retrieveDevices failed: " + e);
        }
        p.log("bt known devices: preknown " + pre + ", cached " + cached);
        showDevices("Spárovaná: " + pre + ", nedávno viděná: " + cached);
    }

    static String cleanAddress(String a) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) b.append(c);
            else if (c >= 'a' && c <= 'f') b.append((char) (c - 32));
        }
        return b.toString();
    }

    void inquiry() {
        if (agent == null) return;
        devices.removeAllElements();
        try {
            // already paired devices first: no inquiry needed
            RemoteDevice[] known = agent.retrieveDevices(DiscoveryAgent.PREKNOWN);
            if (known != null) for (int i = 0; i < known.length; i++) devices.addElement(known[i]);
            agent.startInquiry(DiscoveryAgent.GIAC, this);
            setStatus("Hledám zařízení (~12 s)...");
            p.log("bt inquiry start, prekown " + devices.size());
        } catch (Throwable e) {
            setStatus("Hledání selhalo: " + e);
            p.log("bt inquiry failed: " + e);
        }
    }

    public void deviceDiscovered(RemoteDevice d, DeviceClass cod) {
        for (int i = 0; i < devices.size(); i++)
            if (((RemoteDevice) devices.elementAt(i)).getBluetoothAddress().equals(d.getBluetoothAddress())) return;
        devices.addElement(d);
    }

    public void inquiryCompleted(int type) {
        String t = type == INQUIRY_COMPLETED ? "dokončeno" : type == INQUIRY_TERMINATED ? "přerušeno" : type == INQUIRY_ERROR ? "CHYBA" : "" + type;
        p.log("bt inquiry done (" + type + " " + t + "), devices " + devices.size());
        showDevices("Hledání: " + t);
    }

    void showDevices(String what) {
        setStatus(what + ", zařízení " + devices.size() + (devices.size() == 0
            ? ". Když je 9300 připojená přes Bluetooth k PC, hledání nefunguje: odpoj PC. Nebo zadej adresu Androidu v Nastavení a dej Připojit." : ""));
        if (devices.size() == 0) return;
        deviceList = new List("Vyber GPS", List.IMPLICIT);
        for (int i = 0; i < devices.size(); i++) {
            RemoteDevice d = (RemoteDevice) devices.elementAt(i);
            String name;
            try { name = d.getFriendlyName(false); } catch (Throwable e) { name = "?"; }
            deviceList.append(name + " " + d.getBluetoothAddress(), null);
            p.log("  device " + d.getBluetoothAddress() + " " + name);
        }
        deviceList.addCommand(Probe.BACK);
        deviceList.setCommandListener(this);
        p.show(deviceList);
    }

    void searchService(RemoteDevice d) {
        p.btAddress = d.getBluetoothAddress();
        p.save();
        serviceUrl = null;
        try {
            agent.searchServices(null, new UUID[] { SPP }, d, this);
            setStatus("Hledám službu SPP na " + p.btAddress + "...");
        } catch (Throwable e) {
            setStatus("Hledání služby selhalo: " + e);
            p.log("bt service search failed: " + e);
        }
    }

    public void servicesDiscovered(int transId, ServiceRecord[] recs) {
        for (int i = 0; i < recs.length && serviceUrl == null; i++) {
            serviceUrl = recs[i].getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
        }
    }

    boolean scanChannels;

    public void serviceSearchCompleted(int transId, int resp) {
        p.log("bt service search done: resp " + resp + " (1=ok 2=terminated 3=error 4=no records 6=not reachable), url " + serviceUrl);
        if (serviceUrl != null) connect(serviceUrl);
        else if (scanChannels) {
            // no service record: try RFCOMM channels directly
            String[] urls = new String[10];
            for (int i = 0; i < urls.length; i++)
                urls[i] = "btspp://" + p.btAddress + ":" + (i + 1) + ";authenticate=false;encrypt=false;master=false";
            setStatus("Služba SPP nenalezena (kód " + resp + "), zkouším kanály 1-10...");
            connectAny(urls);
        }
        else setStatus("Služba SPP nenalezena (kód " + resp + "). Běží na Androidu sdílení GPS?");
        scanChannels = false;
    }

    String[] urls;

    void connect(String url) {
        connectAny(new String[] { url });
    }

    void connectAny(String[] list) {
        stop();
        running = true;
        urls = list;
        new Thread(this).start();
    }

    void stop() {
        running = false;
        // closing from this thread is fine here: it's our own Bluetooth stream, not HttpConnection
        try { if (conn != null) conn.close(); } catch (Throwable e) {}
        conn = null;
    }

    public void run() {
        String url = null;
        long t0 = System.currentTimeMillis();
        int sentences = 0, bytes = 0, fixes = 0;
        try {
            for (int i = 0; i < urls.length && running && conn == null; i++) {
                url = urls[i];
                setStatus("Připojuji " + url);
                p.log("bt connect " + url);
                try {
                    conn = (StreamConnection) Connector.open(url);
                } catch (IOException e) {
                    p.log("  failed after " + (System.currentTimeMillis() - t0) + " ms: " + e);
                    if (i == urls.length - 1) throw e;
                }
            }
            if (conn == null) return;
            p.btAddress = cleanAddress(url.substring(8, Math.min(url.length(), 20)));
            p.save();
            p.log("bt connected: " + url);
            InputStream in = conn.openInputStream();
            long tc = System.currentTimeMillis();
            p.log("bt connected in " + (tc - t0) + " ms");
            setStatus("Připojeno (" + (tc - t0) + " ms)");
            StringBuffer line = new StringBuffer();
            long lastUi = 0;
            int ch;
            while (running && (ch = in.read()) >= 0) {
                bytes++;
                if (ch == '\n' || ch == '\r') {
                    if (line.length() > 0) {
                        String s = line.toString();
                        line.setLength(0);
                        sentences++;
                        if (sentences <= 15) p.log("nmea " + s);
                        if (Nmea.parse(s)) fixes++;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 1000) {
                            lastUi = now;
                            raw.setText(s);
                            pos.setText(Nmea.describe());
                            long secs = Math.max(1, (now - tc) / 1000);
                            stats.setText(sentences + " vět, " + bytes + " B, " + (bytes / secs) + " B/s, poloh " + fixes);
                        }
                    }
                } else if (line.length() < 200) {
                    line.append((char) ch);
                }
            }
            p.log("bt stream ended: " + sentences + " sentences, " + bytes + " B, fixes " + fixes + ", last: " + Nmea.describe());
            setStatus("Odpojeno");
        } catch (Throwable e) {
            p.log("bt error after " + (System.currentTimeMillis() - t0) + " ms: " + e + "; sentences " + sentences + ", last: " + Nmea.describe());
            setStatus("Chyba: " + e);
        } finally {
            try { if (conn != null) conn.close(); } catch (Throwable e) {}
            conn = null;
        }
    }
}
