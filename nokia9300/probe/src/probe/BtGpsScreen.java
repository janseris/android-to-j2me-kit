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
    static final Command CONNECT = new Command("Připojit na adresu", Command.SCREEN, 2);
    static final Command STOP = new Command("Odpojit", Command.STOP, 3);
    static final Command PAUSE = new Command("Test: 20 s nečíst", Command.SCREEN, 2);
    static final Command KNOWN = new Command("Spárovaná zařízení (bez hledání)", Command.SCREEN, 1);
    static final UUID SPP = new UUID(0x1101);
    static final Command KEEP = new Command("Keep reading, back to menu", Command.SCREEN, 3);
    /** The GPS reading in the background (for the "like Mapy" speed test), and its sentence count. */
    static volatile BtGpsScreen active;
    static volatile int bgSentences;

    final Probe p = Probe.app;
    final TextField address = new TextField("BT adresa Androidu (Nastavení > O telefonu > Stav)", "", 17, TextField.ANY);
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
        address.setString(p.btAddress);
        append(address); append(status); append(pos); append(stats); append(raw);
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

    // Live values, drawn by the Live canvas. The Form's items are NOT updated from the reader
    // thread any more: probe 1.9 crashed in the UI thread (KERN-EXEC 3 in "main") after a few
    // positions while the form was being updated from the Bluetooth thread every second.
    volatile String vStatus = "", vPos = "-", vStats = "-", vRaw = "-";
    final Live live = new Live();

    void setStatus(String s) {
        vStatus = s;
        live.repaint();
    }

    /** Full-screen live view of the GPS stream; Back returns to the form. */
    class Live extends Canvas implements CommandListener {
        Live() {
            addCommand(PAUSE);
            addCommand(KEEP);
            addCommand(STOP);
            addCommand(Probe.BACK);
            setCommandListener(this);
        }

        public void commandAction(Command c, Displayable d) {
            if (c == PAUSE) {
                // Mapy's crash suspect: data piling up in the Bluetooth buffer while nothing reads it
                pauseUntil = System.currentTimeMillis() + 20000;
                p.log("bt pause test: not reading for 20 s");
                p.saveLog();
                return;
            }
            if (c == KEEP) { p.log("bt: reading continues in the background"); p.back(); return; }
            if (c == STOP) BtGpsScreen.this.stop();
            status.setText(vStatus);              // on the UI thread
            pos.setText(vPos);
            stats.setText(vStats);
            raw.setText(vRaw);
            p.show(BtGpsScreen.this);
        }

        protected void paint(Graphics g) {
            int w = getWidth(), h = getHeight();
            g.setColor(0x1E1F22);
            g.fillRect(0, 0, w, h);
            Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
            g.setFont(f);
            int y = 2;
            y = lines(g, f, 0xFCEE74, "Stav: " + vStatus, y, w);
            y = lines(g, f, 0xFFFFFF, "Poloha: " + vPos, y, w);
            y = lines(g, f, 0xB5BAC1, vStats, y, w);
            lines(g, f, 0x80848E, vRaw, y, w);
        }

        int lines(Graphics g, Font f, int color, String s, int y, int w) {
            g.setColor(color);
            int start = 0;
            while (start < s.length()) {
                int nl = s.indexOf('\n', start);
                String part = nl < 0 ? s.substring(start) : s.substring(start, nl);
                start = nl < 0 ? s.length() : nl + 1;
                while (part.length() > 0) {
                    int n = part.length();
                    while (n > 1 && f.substringWidth(part, 0, n) > w - 6) n--;
                    g.drawString(part.substring(0, n), 3, y, Graphics.TOP | Graphics.LEFT);
                    y += f.getHeight();
                    part = part.substring(n);
                }
            }
            return y + 2;
        }
    }

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
            String a = cleanAddress(address.getString());
            if (a.length() != 12) { setStatus("Zadej BT adresu Androidu nahoře (12 hex znaků, např. 00:11:22:AA:BB:CC). Android: Nastavení > O telefonu > Stav > Adresa Bluetooth."); return; }
            p.btAddress = a;
            p.save();
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
        address.setString(p.btAddress);
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
        p.show(live);
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

    // Reading: only as many bytes as available() says are waiting (read(byte[]) of a fixed 512 crashed
    // with E32USER-CBase 40, single-byte read() with KERN-EXEC 3 in jes-...-java-comms). If available()
    // never reports data, fall back to single bytes.
    InputStream src;
    byte[] rb = new byte[2048];
    int rpos, rlen, zeroAvail;
    boolean availBroken;

    volatile long pauseUntil;
    int maxAvail;

    int next() throws IOException {
        while (running) {
            if (rpos < rlen) return rb[rpos++] & 0xff;
            if (System.currentTimeMillis() < pauseUntil) {
                vStatus = "TEST: nečtu, data se hromadí (" + (pauseUntil - System.currentTimeMillis()) / 1000 + " s)";
                try { Thread.sleep(200); } catch (InterruptedException e) {}
                continue;
            }
            int av = src.available();
            if (av > maxAvail) {
                maxAvail = av;
                p.log("bt backlog " + av + " B");
                p.saveLog();
            }
            if (av > 0) {
                // read EXACTLY what available() says: a read of a different length (more: probe 1.7,
                // less: after a backlog bigger than the buffer) crashed with E32USER-CBase 40
                if (av > rb.length) rb = new byte[av + 512];
                int n = src.read(rb, 0, av);
                if (n < 0) return -1;
                rpos = 0;
                rlen = n;
                zeroAvail = 0;
                continue;
            }
            // no blocking single-byte fallback: a GPS without a fix may send nothing for minutes,
            // and single-byte reads crashed the comms thread (KERN-EXEC 3, probe 1.8/1.9, Mapy 3.0)
            ++zeroAvail;
            try { Thread.sleep(50); } catch (InterruptedException e) {}
        }
        return -1;
    }

    int reconnects;
    String lastError = "";

    /** Keeps the GPS connected: after the stream ends or fails, waits and connects again. */
    public void run() {
        String[] first = urls;
        reconnects = 0;
        active = this;
        bgSentences = 0;
        while (running) {
            lastError = "";
            session();
            if (!running) break;
            reconnects++;
            // "already exists" (-11): the old link is still being torn down; give it longer
            int wait = lastError.indexOf("-11") >= 0 ? 10 : 3;
            setStatus("Spojení skončilo (" + lastError + "), znovu za " + wait + " s, pokus " + reconnects);
            p.log("bt reconnect " + reconnects + " in " + wait + " s (" + lastError + ")");
            for (int k = 0; k < wait * 10 && running; k++) {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
            urls = first;
        }
        if (active == this) active = null;
    }

    /** One connection: connect, read until the stream ends. */
    void session() {
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
            long lastUi = 0, lastLog = 0;
            // real-time check: GPS time (from RMC) against the phone's receive time. If the
            // difference grows, the data arrives late (buffered somewhere) and we fall behind.
            long firstRecv = 0, lastFixRecv = 0, maxGap = 0;
            double firstGps = -1, lastGpsSec = -1;
            long drift = 0, maxDrift = 0, minDrift = Long.MAX_VALUE;
            int ch0;
            // one byte at a time: on the 9300, read(byte[]) on a Bluetooth stream crashed the Java
            // comms thread (E32USER-CBase 40, probe 1.7); single-byte read() works
            int eofs = 0;
            src = in;
            rpos = rlen = 0;
            zeroAvail = 0;
            availBroken = false;
            while (running) {
                ch0 = next();
                if (ch0 < 0) {
                    // some Bluetooth stacks return -1 while no data is waiting; only 5 s of it means the end
                    if (++eofs > 50) break;
                    if (eofs == 1) p.log("bt read -1 after " + sentences + " sentences, waiting");
                    try { Thread.sleep(100); } catch (InterruptedException ie) {}
                    continue;
                }
                if (eofs > 0) { p.log("bt data again after " + eofs + " x -1"); eofs = 0; }
                bytes++;
                for (int k = 0; k < 1; k++) {
                    int ch = ch0;
                    if (ch == '\n' || ch == '\r') {
                        if (line.length() == 0) continue;
                        String s = line.toString();
                        line.setLength(0);
                        sentences++;
                        bgSentences++;
                        if (sentences <= 15) { p.log("nmea " + s); p.saveLog(); }      // saved at once: it crashed here
                        long now = System.currentTimeMillis();
                        if (Nmea.parse(s)) {
                            fixes++;
                            double gs = Nmea.secondsOfDay();
                            if (gs >= 0 && gs != lastGpsSec) {           // a new GPS second
                                if (firstGps < 0) { firstGps = gs; firstRecv = now; }
                                double dg = gs - firstGps;
                                if (dg < -43200) dg += 86400;               // past midnight
                                drift = (now - firstRecv) - (long) (dg * 1000);
                                if (drift > maxDrift) maxDrift = drift;
                                if (drift < minDrift) minDrift = drift;
                                if (lastFixRecv > 0 && now - lastFixRecv > maxGap) maxGap = now - lastFixRecv;
                                lastFixRecv = now;
                                lastGpsSec = gs;
                            }
                        }
                        if (now - lastUi > 1000) {
                            lastUi = now;
                            vRaw = s;
                            vPos = Nmea.describe();
                            long secs = Math.max(1, (now - tc) / 1000);
                            vStats = (sentences + " vět, " + bytes + " B, " + (bytes / secs) + " B/s, poloh " + fixes
                                + "\nzpoždění oproti začátku: " + drift + " ms (rozsah " + (minDrift == Long.MAX_VALUE ? 0 : minDrift) + ".." + maxDrift
                                + "), největší mezera mezi polohami " + maxGap + " ms"
                                + (lastFixRecv > 0 ? ", poslední poloha před " + (now - lastFixRecv) + " ms" : ""));
                            live.repaint();
                        }
                        if (now - lastLog > 5000 && lastGpsSec >= 0) {
                            lastLog = now;
                            p.log("fix gps " + Nmea.time + " drift " + drift + " ms (min " + minDrift + " max " + maxDrift + "), gap max " + maxGap
                                + " ms, " + Nmea.fmt(Nmea.lat, 6) + "," + Nmea.fmt(Nmea.lon, 6) + " " + Nmea.fmt(Nmea.speedKmh, 1) + " km/h, sats "
                                + Nmea.sats + ", " + sentences + " sentences, " + (bytes * 1000 / Math.max(1, now - tc)) + " B/s");
                        }
                    } else if (line.length() < 200) {
                        line.append((char) ch);
                    }
                }
            }
            p.log("bt stream ended: " + sentences + " sentences, " + bytes + " B, fixes " + fixes + ", last: " + Nmea.describe());
            setStatus("Odpojeno");
        } catch (Throwable e) {
            p.log("bt error after " + (System.currentTimeMillis() - t0) + " ms: " + e + "; sentences " + sentences + ", last: " + Nmea.describe());
            setStatus("Chyba: " + e);
            lastError = e.toString();
        } finally {
            try { if (conn != null) conn.close(); } catch (Throwable e) {}
            conn = null;
        }
    }
}
