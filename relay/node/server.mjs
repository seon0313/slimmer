// Slimmer relay — self-hosted Node.js version (same protocol as the Cloudflare Worker).
// Put it behind a TLS terminator (Caddy, nginx, Cloudflare Tunnel, ...) and use wss://.
import http from "node:http";
import { WebSocketServer } from "ws";

const PORT = Number(process.env.PORT || 8787);
const ROOM_RE = /^\/v1\/room\/([0-9a-f]{32})$/;
const rooms = new Map(); // roomId -> { mac: ws|null, phone: ws|null }
const other = (role) => (role === "mac" ? "phone" : "mac");

const server = http.createServer((req, res) => {
  res.writeHead(req.url === "/" || req.url === "/health" ? 200 : 404, { "content-type": "text/plain" });
  res.end(req.url === "/" || req.url === "/health" ? "slimmer relay ok\n" : "not found\n");
});

const wss = new WebSocketServer({ noServer: true, maxPayload: 4 * 1024 * 1024 });

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, "http://x");
  const m = url.pathname.match(ROOM_RE);
  const role = url.searchParams.get("role");
  if (!m || (role !== "mac" && role !== "phone")) {
    socket.write("HTTP/1.1 400 Bad Request\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => join(m[1], role, ws));
});

function send(ws, data) {
  if (ws && ws.readyState === 1) ws.send(data);
}

function join(roomId, role, ws) {
  let room = rooms.get(roomId);
  if (!room) rooms.set(roomId, (room = { mac: null, phone: null }));
  if (room[role]) room[role].close(4000, "replaced");
  room[role] = ws;
  ws.isAlive = true;

  const peer = room[other(role)];
  send(ws, JSON.stringify({ t: "peer", online: !!peer }));
  send(peer, JSON.stringify({ t: "peer", online: true }));

  ws.on("pong", () => (ws.isAlive = true));
  ws.on("message", (data, isBinary) => {
    ws.isAlive = true;
    if (!isBinary) {
      if (data.toString() === "ping") send(ws, "pong");
      return;
    }
    send(room[other(role)], data);
  });
  ws.on("close", () => {
    if (room[role] !== ws) return;
    room[role] = null;
    send(room[other(role)], JSON.stringify({ t: "peer", online: false }));
    if (!room.mac && !room.phone) rooms.delete(roomId);
  });
  ws.on("error", () => {});
}

// Drop dead sockets (phones that vanished without closing).
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000);

server.listen(PORT, () => console.log(`slimmer relay listening on :${PORT}`));
