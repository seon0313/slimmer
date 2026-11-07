// Slimmer relay — Cloudflare Worker + Durable Object (one object per pairing room).
//
// The relay only forwards opaque, end-to-end encrypted binary frames between the
// two members of a room ("mac" and "phone"). It never sees keys or plaintext.
//
//   GET /v1/room/<32 hex>?role=mac|phone   (WebSocket upgrade)
//
// Text frames from the relay are control messages: {"t":"peer","online":bool}.

const ROOM_RE = /^\/v1\/room\/([0-9a-f]{32})$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response("slimmer relay ok\n", { headers: { "content-type": "text/plain" } });
    }
    const m = url.pathname.match(ROOM_RE);
    if (!m) return new Response("not found\n", { status: 404 });
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket\n", { status: 426 });
    }
    const role = url.searchParams.get("role");
    if (role !== "mac" && role !== "phone") return new Response("bad role\n", { status: 400 });
    const stub = env.ROOMS.get(env.ROOMS.idFromName(m[1]));
    return stub.fetch(request);
  },
};

const other = (role) => (role === "mac" ? "phone" : "mac");

export class Room {
  constructor(state) {
    this.state = state;
    // Answer app-level keep-alives without waking the object.
    this.state.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request) {
    const role = new URL(request.url).searchParams.get("role");
    const [client, server] = Object.values(new WebSocketPair());

    // One socket per role: a reconnecting device replaces its stale socket.
    for (const old of this.state.getWebSockets(role)) {
      try { old.close(4000, "replaced"); } catch {}
    }
    this.state.acceptWebSocket(server, [role]);

    const peers = this.state.getWebSockets(other(role));
    server.send(JSON.stringify({ t: "peer", online: peers.length > 0 }));
    for (const p of peers) {
      try { p.send(JSON.stringify({ t: "peer", online: true })); } catch {}
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  roleOf(ws) {
    return this.state.getTags(ws)[0];
  }

  async webSocketMessage(ws, message) {
    if (typeof message === "string") return; // control frames from clients are ignored
    if (message.byteLength > 4 * 1024 * 1024) return;
    for (const p of this.state.getWebSockets(other(this.roleOf(ws)))) {
      try { p.send(message); } catch {}
    }
  }

  gone(ws) {
    const role = this.roleOf(ws);
    const still = this.state.getWebSockets(role).some((w) => w !== ws && w.readyState === 1);
    if (still) return;
    for (const p of this.state.getWebSockets(other(role))) {
      try { p.send(JSON.stringify({ t: "peer", online: false })); } catch {}
    }
  }

  async webSocketClose(ws, code) {
    this.gone(ws);
    try { ws.close(code === 1005 ? 1000 : code, "bye"); } catch {}
  }

  async webSocketError(ws) {
    this.gone(ws);
  }
}
