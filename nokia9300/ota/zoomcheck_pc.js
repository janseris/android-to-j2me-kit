// Which zoom levels each map type of Mapy 9300 really has: one tile (Olomouc centre) per zoom 16-21
// from every server, with the HTTP status, content type and size. A server that ends at a zoom
// answers 404 (or 400, or an empty / "no data" image) beyond it.
// Prints the table and saves it to uploads/<time>_zoomcheck_pc.txt.
//
// Usage (in this folder):  node zoomcheck_pc.js
// Mapy.com needs mapy_api.key next to this script (the same file the phone loads).

const https = require("https"), fs = require("fs"), path = require("path");

const UA = "Mapy9300native/0.4 (zoom check from the PC; Nokia 9300 map app)";
let key = "";
try { key = fs.readFileSync(path.join(__dirname, "mapy_api.key"), "utf8").trim(); } catch (e) {}

const LAYERS = [
    ["OpenStreetMap", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
    ["OpenTopoMap", "https://tile.opentopomap.org/{z}/{x}/{y}.png"],
    ["CUZK base", "https://ags.cuzk.gov.cz/arcgis1/rest/services/ZTM_WM/MapServer/tile/{z}/{y}/{x}"],
    ["CUZK aerial", "https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO_WM/MapServer/tile/{z}/{y}/{x}"],
    ["Mapy.com standard", "https://api.mapy.com/v1/maptiles/basic/256/{z}/{x}/{y}?apikey={key}"],
    ["Mapy.com outdoor", "https://api.mapy.com/v1/maptiles/outdoor/256/{z}/{x}/{y}?apikey={key}"],
    ["Mapy.com aerial", "https://api.mapy.com/v1/maptiles/aerial/256/{z}/{x}/{y}?apikey={key}"],
];
const LAT = 49.5938, LON = 17.2509;

function tile(z) {
    const n = 2 ** z, r = LAT * Math.PI / 180;
    return [Math.floor((LON + 180) / 360 * n), Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n)];
}

function get(u) {
    return new Promise(resolve => {
        const req = https.get(u, { headers: { "User-Agent": UA } }, res => {
            let bytes = 0;
            res.on("data", d => bytes += d.length);
            res.on("end", () => resolve(res.statusCode + " " + (res.headers["content-type"] || "").split(";")[0].replace("image/", "") + " " + bytes + "B"));
        });
        req.on("error", e => resolve("ERR " + e.code));
        req.setTimeout(20000, () => req.destroy(new Error("timeout")));
    });
}

(async () => {
    let out = "Zoom check " + new Date().toISOString() + " (one tile in Olomouc per zoom)\n";
    if (!key) out += "No mapy_api.key here: Mapy.com skipped\n";
    for (const [name, t] of LAYERS) {
        if (t.includes("{key}") && !key) continue;
        const cells = [];
        for (let z = 16; z <= 21; z++) {
            const [x, y] = tile(z);
            cells.push("z" + z + ": " + await get(t.replace("{z}", z).replace("{x}", x).replace("{y}", y).replace("{key}", key)));
        }
        const line = name.padEnd(18) + cells.join(" | ");
        console.log(line);
        out += line + "\n";
    }
    const dir = path.join(__dirname, "uploads");
    fs.mkdirSync(dir, { recursive: true });
    const f = path.join(dir, new Date().toISOString().replace(/[:.]/g, "-") + "_zoomcheck_pc.txt");
    fs.writeFileSync(f, out);
    console.log("saved " + f);
})();
