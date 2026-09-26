import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { WebSocketServer, WebSocket } from "ws";

const port = Number(process.env.PORT || 9876);
const maxItemsPerSpace = 10000;
const rooms = new Map();
const dataDirectory = path.resolve(process.env.RELAY_DATA_DIR || "data");
fs.mkdirSync(dataDirectory, { recursive: true });

function roomFile(spaceId) {
  const name = crypto.createHash("sha256").update(spaceId).digest("hex");
  return path.join(dataDirectory, `${name}.jsonl`);
}

function loadRoom(spaceId) {
  const file = roomFile(spaceId);
  if (!fs.existsSync(file)) return null;
  const records = fs.readFileSync(file, "utf8").split("\n").filter(Boolean).map(line => JSON.parse(line));
  const headers = records.filter(record => record.type === "room");
  if (headers.length !== 1 || headers[0].spaceId !== spaceId || typeof headers[0].accessProof !== "string") {
    throw new Error("CORRUPT ROOM HEADER");
  }
  const room = { accessProof: headers[0].accessProof, clients: new Set(), items: new Map() };
  for (const record of records) {
    if (record.type !== "item") continue;
    const item = record.item;
    if (!item || ![item.id, item.kind, item.iv, item.ciphertext].every(value => typeof value === "string")) {
      throw new Error("CORRUPT ROOM ITEM");
    }
    if (item.kind !== "event" && item.kind !== "pair_accept") {
      throw new Error("INVALID PERSISTENT ITEM");
    }
    room.items.set(item.id, item);
  }
  return room;
}

function appendRecord(spaceId, record) {
  fs.appendFileSync(roomFile(spaceId), `${JSON.stringify(record)}\n`, { encoding: "utf8", mode: 0o600 });
}

function roomFor(spaceId, accessProof) {
  const fileExists = fs.existsSync(roomFile(spaceId));
  let current = rooms.get(spaceId);
  if (!current && fileExists) {
    try {
      current = loadRoom(spaceId);
    } catch {
      return null;
    }
  }
  if (current && current.accessProof !== accessProof) return null;
  if (current) {
    rooms.set(spaceId, current);
    return current;
  }
  if (fileExists) return null;
  const created = { accessProof, clients: new Set(), items: new Map() };
  rooms.set(spaceId, created);
  appendRecord(spaceId, { type: "room", spaceId, accessProof, createdAt: Date.now() });
  return created;
}

function send(socket, value) {
  if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value));
}

function broadcastPresence(spaceId, room) {
  const message = { type: "presence", spaceId, count: room.clients.size };
  for (const client of room.clients) send(client, message);
}

const server = http.createServer((request, response) => {
  if (request.url === "/health") {
    response.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
    response.end(JSON.stringify({ ok: true, protocol: 1 }));
    return;
  }
  response.writeHead(404).end();
});

const websocketServer = new WebSocketServer({ server, path: "/v1/ws", maxPayload: 1024 * 1024 });
websocketServer.on("connection", socket => {
  socket.joinedSpaces = new Set();
  socket.on("message", raw => {
    let message;
    try { message = JSON.parse(raw.toString("utf8")); } catch { return send(socket, { type: "error", message: "INVALID MESSAGE" }); }
    if (message.type === "join") {
      if (typeof message.spaceId !== "string" || typeof message.accessProof !== "string") return;
      const room = roomFor(message.spaceId, message.accessProof);
      if (!room) return send(socket, { type: "error", message: "SPACE ACCESS DENIED" });
      room.clients.add(socket);
      socket.joinedSpaces.add(message.spaceId);
      for (const item of room.items.values()) send(socket, { type: "item", spaceId: message.spaceId, ...item });
      broadcastPresence(message.spaceId, room);
      return;
    }
    if (message.type === "publish") {
      const room = rooms.get(message.spaceId);
      if (!room || !room.clients.has(socket)) return send(socket, { type: "error", message: "JOIN REQUIRED" });
      if (![message.id, message.kind, message.iv, message.ciphertext].every(value => typeof value === "string")) return;
      const item = { id: message.id, kind: message.kind, iv: message.iv, ciphertext: message.ciphertext };
      const persistent = message.kind === "event" || message.kind === "pair_accept";
      if (!persistent) {
        for (const client of room.clients) if (client !== socket) send(client, { type: "item", spaceId: message.spaceId, ...item });
      } else if (!room.items.has(message.id)) {
        if (room.items.size >= maxItemsPerSpace) return send(socket, { type: "error", message: "SPACE EVENT LIMIT REACHED" });
        room.items.set(message.id, item);
        appendRecord(message.spaceId, { type: "item", item, storedAt: Date.now() });
        for (const client of room.clients) if (client !== socket) send(client, { type: "item", spaceId: message.spaceId, ...item });
      }
      send(socket, { type: "ack", id: message.id });
    }
  });
  socket.on("close", () => {
    for (const spaceId of socket.joinedSpaces) {
      const room = rooms.get(spaceId);
      if (!room) continue;
      room.clients.delete(socket);
      broadcastPresence(spaceId, room);
    }
  });
});

server.listen(port, "0.0.0.0", () => {
  process.stdout.write(`ONaline relay listening on ${port}\n`);
});
