// RemotePhone relay server.
// - Serves the web controller (public/index.html)
// - Pairs a phone and a viewer by an 8-digit code shown on the phone
// - Forwards screen frames phone -> viewer and input events viewer -> phone
// Run behind HTTPS (wss://) in production: put it behind Caddy / nginx / a host that gives TLS.

const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 8080;
const rooms = new Map(); // code -> { phone, viewer }
const failures = new Map(); // ip -> { count, resetAt }

const MAX_FAILS = 10;
const FAIL_WINDOW_MS = 10 * 60 * 1000;

function clientIp(req) {
  return (req.headers["x-forwarded-for"] || req.socket.remoteAddress || "").split(",")[0].trim();
}
function blocked(ip) {
  const f = failures.get(ip);
  if (!f) return false;
  if (Date.now() > f.resetAt) { failures.delete(ip); return false; }
  return f.count >= MAX_FAILS;
}
function recordFail(ip) {
  const f = failures.get(ip);
  if (!f || Date.now() > f.resetAt) failures.set(ip, { count: 1, resetAt: Date.now() + FAIL_WINDOW_MS });
  else f.count++;
}

const server = http.createServer((req, res) => {
  if (req.url === "/" || req.url === "/index.html") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    fs.createReadStream(path.join(__dirname, "public", "index.html")).pipe(res);
  } else if (req.url === "/health") {
    res.writeHead(200); res.end("ok");
  } else {
    res.writeHead(404); res.end("Not found");
  }
});

const wss = new WebSocketServer({ server, path: "/ws", maxPayload: 8 * 1024 * 1024 });

function send(ws, data, binary) {
  if (ws && ws.readyState === 1) ws.send(data, { binary: !!binary });
}

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, "http://localhost");
  const role = url.searchParams.get("role");
  const code = (url.searchParams.get("code") || "").trim();
  const ip = clientIp(req);

  if (!/^\d{8}$/.test(code) || (role !== "phone" && role !== "viewer")) {
    ws.close(4000, "bad request");
    return;
  }

  if (role === "phone") {
    const existing = rooms.get(code);
    if (existing && existing.phone && existing.phone.readyState === 1) {
      ws.close(4001, "code in use");
      return;
    }
    const room = existing || { phone: null, viewer: null };
    room.phone = ws;
    rooms.set(code, room);
    if (room.viewer) send(ws, JSON.stringify({ type: "viewer", connected: true }));

    ws.on("message", (data, isBinary) => {
      if (room.viewer && room.viewer.readyState === 1) {
        // drop frames if the viewer is falling behind
        if (isBinary && room.viewer.bufferedAmount > 2 * 1024 * 1024) return;
        send(room.viewer, data, isBinary);
      }
    });
    ws.on("close", () => {
      if (room.phone === ws) room.phone = null;
      send(room.viewer, JSON.stringify({ type: "phone", connected: false }));
      if (!room.phone && !room.viewer) rooms.delete(code);
    });
    return;
  }

  // viewer
  if (blocked(ip)) { ws.close(4029, "too many attempts"); return; }
  const room = rooms.get(code);
  if (!room || !room.phone || room.phone.readyState !== 1) {
    recordFail(ip);
    ws.close(4004, "no phone with that code");
    return;
  }
  if (room.viewer && room.viewer.readyState === 1) room.viewer.close(4002, "replaced by new viewer");
  room.viewer = ws;
  send(room.phone, JSON.stringify({ type: "viewer", connected: true }));
  send(ws, JSON.stringify({ type: "phone", connected: true }));

  ws.on("message", (data, isBinary) => {
    if (isBinary) return;
    send(room.phone, data.toString(), false);
  });
  ws.on("close", () => {
    if (room.viewer === ws) {
      room.viewer = null;
      send(room.phone, JSON.stringify({ type: "viewer", connected: false }));
    }
    if (!room.phone && !room.viewer) rooms.delete(code);
  });
});

server.listen(PORT, () => console.log(`RemotePhone server on http://localhost:${PORT}`));
