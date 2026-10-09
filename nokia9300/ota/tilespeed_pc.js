// The phone's map server test from the PC, to tell the servers' own delay from the phone's.
// Same servers, same kind of tiles (Brno, zoom 16), same User-Agent as Probe on the Nokia 9300.
// Each tile once with a new connection (as the phone's Java does) and once over a kept-open
// connection (as Net Helper does). Prints the times and saves them to uploads/<time>_tilespeed_pc.txt.
//
// Usage (in this folder):  node tilespeed_pc.js
// Mapy.com tiles need mapy_api.key next to this script (the same file the phone loads).

const http = require("http"), https = require("https"), fs = require("fs"), path = require("path");

const UA = "Probe9300/3.7 (J2ME device test; Nokia 9300; SymbianOS/7.0s Series80/2.0; Profile/MIDP-2.0 Configuration/CLDC-1.1)";
const N = 6;
let key = "";
try { key = fs.readFileSync(path.join(__dirname, "mapy_api.key"), "utf8").trim(); } catch (e) {}

const SERVERS = [
    ["OSM https", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
    ["OpenTopoMap http", "http://tile.opentopomap.org/{z}/{x}/{y}.png"],
    ["ČÚZK base http", "http://ags.cuzk.gov.cz/arcgis1/rest/services/ZTM_WM/MapServer/tile/{z}/{y}/{x}"],
    ["Mapy.com standard", "https://api.mapy.com/v1/maptiles/basic/256/{z}/{x}/{y}?apikey={key}"],
    ["Mapy.com outdoor", "https://api.mapy.com/v1/maptiles/outdoor/256/{z}/{x}/{y}?apikey={key}"],
    ["Mapy.com aerial", "https://api.mapy.com/v1/maptiles/aerial/256/{z}/{x}/{y}?apikey={key}"],
];

let out = "";
function line(s) { console.log(s); out += s + "\n"; }

function url(t, z, x, y) { return t.replace("{z}", z).replace("{x}", x).replace("{y}", y).replace("{key}", key); }

/** One GET: { ms total, first byte ms, code, bytes }. */
function get(u, agent) {
    return new Promise(resolve => {
        const lib = u.startsWith("https") ? https : http;
        const t0 = Date.now();
        let first = -1, bytes = 0;
        const req = lib.get(u, { agent, headers: { "User-Agent": UA } }, res => {
            res.on("data", d => { if (first < 0) first = Date.now() - t0; bytes += d.length; });
            res.on("end", () => resolve({ ms: Date.now() - t0, first, code: res.statusCode, bytes }));
        });
        req.on("error", e => resolve({ ms: Date.now() - t0, first: -1, code: "ERR " + e.code, bytes: 0 }));
        req.setTimeout(20000, () => req.destroy(new Error("timeout")));
    });
}

function avg(a) {
    const v = a.slice(1).filter(r => r.code === 200).map(r => r.ms);
    return v.length ? Math.round(v.reduce((s, x) => s + x, 0) / v.length) : "error";
}

(async () => {
    line("Map server speed from the PC, " + new Date().toISOString() + ", User-Agent: " + UA);
    if (!key) line("No mapy_api.key here: Mapy.com skipped");
    const summary = [];
    for (let s = 0; s < SERVERS.length; s++) {
        const [name, t] = SERVERS[s];
        if (t.includes("{key}") && !key) continue;
        const x0 = 35800 + 10 * s + 3, y = 22210;          // near the phone's tiles, not the same ones
        line("== " + name);
        const fresh = [], kept = [];
        for (let i = 0; i < N; i++) fresh.push(await get(url(t, 16, x0 + i, y), false));       // new connection each
        const agent = new (t.startsWith("https") ? https : http).Agent({ keepAlive: true, maxSockets: 1 });
        for (let i = 0; i < N; i++) kept.push(await get(url(t, 16, x0 + i, y + 1), agent));      // one kept-open connection
        agent.destroy();
        const fmt = a => "[" + a.map(r => r.ms + (r.code === 200 ? "" : "(" + r.code + ")")).join(" ") + "] ms, first bytes after [" + a.map(r => r.first).join(" ") + "] ms";
        line("new connection each: " + fmt(fresh));
        line("kept-open connection: " + fmt(kept));
        summary.push(name + ": " + avg(fresh) + " / " + avg(kept));
    }
    line("== Summary (ms per tile without the first one: new connection each / kept-open connection)");
    summary.forEach(line);
    const dir = path.join(__dirname, "uploads");
    fs.mkdirSync(dir, { recursive: true });
    const f = path.join(dir, new Date().toISOString().replace(/[:.]/g, "-") + "_tilespeed_pc.txt");
    fs.writeFileSync(f, out);
    console.log("saved " + f);
})();
