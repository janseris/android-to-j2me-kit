// Over-the-air (OTA) install server for the Nokia 9300: serves this folder over HTTP
// with the MIME types MIDP phones expect, and rewrites MIDlet-Jar-URL in .jad files to
// an absolute http:// URL on this server (the JAR signature doesn't cover the JAD).
//
// Usage (in this folder):  node ota_server.js [port]
// Then open http://<PC address>:<port>/ in the phone's browser and pick a .jad.
const http = require("http");
const fs = require("fs");
const path = require("path");
const os = require("os");
const net = require("net");

const port = Number(process.argv[2] || 8000);
const dir = __dirname;
const types = { ".jad": "text/vnd.sun.j2me.app-descriptor", ".jar": "application/java-archive",
                ".cer": "application/x-x509-ca-cert", ".der": "application/x-x509-ca-cert" };

http.createServer((req, res) => {
    const url = decodeURIComponent(req.url.split("?")[0]);
    console.log(new Date().toISOString().slice(11, 19), req.method, url, "from", req.socket.remoteAddress,
        "UA:", req.headers["user-agent"] || "-");
    const extra = ["range", "if-range", "if-modified-since", "accept", "connection"].filter(h => req.headers[h]).map(h => h + "=" + req.headers[h]);
    if (extra.length) console.log("  headers:", extra.join(" | "));
    if (req.method === "POST" && url === "/upload") { // file upload from the phone's browser
        const chunks = []; req.on("data", d => chunks.push(d)); req.on("end", () => {
            const body = Buffer.concat(chunks);
            const m = /boundary=(.+)$/.exec(req.headers["content-type"] || "");
            let saved = [];
            if (m) {
                const parts = body.toString("latin1").split("--" + m[1].replace(/"/g, ""));
                for (const part of parts) {
                    const fn = /filename="([^"]*)"/.exec(part);
                    if (!fn || !fn[1]) continue;
                    const start = part.indexOf("\r\n\r\n") + 4;
                    const data = Buffer.from(part.slice(start, part.length - 2), "latin1");
                    const name = path.basename(fn[1].replace(/\\/g, "/")) || "upload.bin";
                    fs.mkdirSync(path.join(dir, "uploads"), { recursive: true });
                    const out = path.join(dir, "uploads", new Date().toISOString().replace(/[:.]/g, "-") + "_" + name);
                    fs.writeFileSync(out, data);
                    saved.push(out + " (" + data.length + " B)");
                }
            }
            console.log("  upload saved:", saved.join(", ") || "nothing");
            res.writeHead(200, { "Content-Type": "text/html" });
            res.end("<html><body>Saved: " + (saved.length ? saved.map(x => path.basename(x)).join(", ") : "nothing") + "<br><a href=/upload>again</a></body></html>");
        });
        return;
    }
    if (req.method === "POST" && url === "/results") { // test results POSTed by the app (TlsTestScreen)
        const chunks = []; req.on("data", d => chunks.push(d)); req.on("end", () => {
            fs.mkdirSync(path.join(dir, "uploads"), { recursive: true });
            const nm = (/[?&]name=([A-Za-z0-9_-]+)/.exec(req.url) || [0, "results"])[1];
            const out = path.join(dir, "uploads", new Date().toISOString().replace(/[:.]/g, "-") + "_" + nm + ".txt");
            const body = Buffer.concat(chunks);
            fs.writeFileSync(out, body);
            console.log("  results saved:", out, "(" + body.length + " B)");
            console.log(body.toString("utf8"));
            res.writeHead(200, { "Content-Type": "text/plain" });
            res.end("saved");
        });
        return;
    }
    if (url === "/headers") { // echo the request headers: shows what the phone really sends (e.g. User-Agent)
        const text = req.method + " " + req.url + "\n" + Object.keys(req.headers).map(h => h + ": " + req.headers[h]).join("\n") + "\n";
        console.log(text);
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.end(text);
        return;
    }
    if (url === "/upload") {
        res.writeHead(200, { "Content-Type": "text/html" });
        res.end('<html><body><h3>Upload</h3><form method="post" action="/upload" enctype="multipart/form-data">'
            + '<input type="file" name="f"><br><input type="submit" value="Upload"></form></body></html>');
        return;
    }
    if (req.method === "POST") { // MIDlet-Install-Notify etc.
        let body = ""; req.on("data", d => body += d); req.on("end", () => { console.log("  POST body:", body); res.end(); });
        return;
    }
    if (url === "/") {
        const files = fs.readdirSync(dir).filter(f => /\.(jad|jar|cer|dll|txt)$/.test(f));
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
        res.end("<html><body><h3>OTA</h3><a href=/upload>Upload a file to the PC</a><br><br>" + files.map(f => `<a href="/${f}">${f}</a><br>`).join("") + "</body></html>");
        return;
    }
    const file = path.join(dir, path.basename(url));
    if (!fs.existsSync(file)) { res.writeHead(404); res.end("not found"); return; }
    const ext = path.extname(file).toLowerCase();
    let data = fs.readFileSync(file);
    if (ext === ".jad") {
        const host = req.headers.host || ("localhost:" + port);
        data = Buffer.from(data.toString("latin1").replace(/^MIDlet-Jar-URL: *(\S+)/m,
            (m, u) => "MIDlet-Jar-URL: " + (/^https?:/.test(u) ? u : "http://" + host + "/" + u)), "latin1");
    }
    res.writeHead(200, { "Content-Type": types[ext] || "application/octet-stream", "Content-Length": data.length });
    const t0 = Date.now();
    res.on("finish", () => console.log("  sent", data.length, "B in", Date.now() - t0, "ms (complete)"));
    res.on("close", () => { if (!res.writableFinished) console.log("  ABORTED: connection closed before the whole file was sent (" + data.length + " B)"); });
    res.end(data);
}).listen(port, "0.0.0.0", () => {
    console.log("OTA server on port " + port + ". Addresses of this PC:");
    const ifs = os.networkInterfaces();
    for (const n in ifs) for (const a of ifs[n]) if (a.family === "IPv4" && !a.internal) console.log("  http://" + a.address + ":" + port + "/   (" + n + ")");
});

// Raw echo on port+1: answers with the exact bytes of the request (header lines as sent, before any
// HTTP parsing), escaped, and saves them to uploads/. Shows what the phone's HTTP stack really sends.
net.createServer(sock => {
    let buf = Buffer.alloc(0);
    sock.on("data", d => {
        buf = Buffer.concat([buf, d]);
        const end = buf.indexOf("\r\n\r\n");
        if (end < 0 && buf.length < 16384) return;
        const raw = buf.slice(0, end < 0 ? buf.length : end + 4);
        let text = "";
        for (const b of raw) {
            if (b === 13) text += "\\r";
            else if (b === 10) text += "\\n\n";
            else if (b < 32 || b > 126) text += "\\x" + b.toString(16).padStart(2, "0");
            else text += String.fromCharCode(b);
        }
        text = "raw request, " + raw.length + " B:\n" + text;
        console.log(text);
        fs.mkdirSync(path.join(dir, "uploads"), { recursive: true });
        fs.writeFileSync(path.join(dir, "uploads", new Date().toISOString().replace(/[:.]/g, "-") + "_raw.txt"), text);
        const body = Buffer.from(text, "latin1");
        sock.end(Buffer.concat([Buffer.from("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n", "latin1"), body]));
    });
    sock.on("error", () => {});
}).listen(port + 1, "0.0.0.0", () => console.log("raw request echo on port " + (port + 1)));
