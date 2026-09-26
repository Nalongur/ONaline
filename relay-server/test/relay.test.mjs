import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { WebSocket } from "ws";

const port = 18787;
let server;
let dataDirectory;

async function startServer(serverPort, directory) {
  const child = spawn(process.execPath, ["src/server.mjs"], {
    cwd: new URL("..", import.meta.url),
    env: { ...process.env, PORT: String(serverPort), RELAY_DATA_DIR: directory },
    stdio: ["ignore", "pipe", "pipe"],
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("server start timeout")), 3000);
    child.stdout.once("data", () => { clearTimeout(timer); resolve(); });
  });
  return child;
}

function waitFor(socket, predicate, timeout = 3000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("message timeout")), timeout);
    const handler = raw => {
      const message = JSON.parse(raw.toString());
      if (!predicate(message)) return;
      clearTimeout(timer);
      socket.off("message", handler);
      resolve(message);
    };
    socket.on("message", handler);
  });
}

test.before(async () => {
  dataDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "private-canvas-relay-test-"));
  server = await startServer(port, dataDirectory);
});

test.after(() => {
  server?.kill();
  fs.rmSync(dataDirectory, { recursive: true, force: true });
});

test("relays opaque idempotent items only to authorized room members", async () => {
  const first = new WebSocket(`ws://127.0.0.1:${port}/v1/ws`);
  const second = new WebSocket(`ws://127.0.0.1:${port}/v1/ws`);
  await Promise.all([first, second].map(socket => new Promise(resolve => socket.once("open", resolve))));
  const join = { type: "join", spaceId: "space-a", accessProof: "proof-a", deviceId: "device" };
  first.send(JSON.stringify(join));
  second.send(JSON.stringify(join));
  await waitFor(second, message => message.type === "presence" && message.count === 2);

  const received = waitFor(second, message => message.type === "item" && message.id === "event-1");
  const acked = waitFor(first, message => message.type === "ack" && message.id === "event-1");
  first.send(JSON.stringify({ type: "publish", spaceId: "space-a", id: "event-1", kind: "event", iv: "opaque-iv", ciphertext: "opaque-ciphertext" }));
  assert.equal((await received).ciphertext, "opaque-ciphertext");
  await acked;

  const denied = new WebSocket(`ws://127.0.0.1:${port}/v1/ws`);
  await new Promise(resolve => denied.once("open", resolve));
  const error = waitFor(denied, message => message.type === "error");
  denied.send(JSON.stringify({ ...join, accessProof: "wrong" }));
  assert.equal((await error).message, "SPACE ACCESS DENIED");

  const ephemeralReceived = waitFor(second, message => message.type === "item" && message.id === "request-1");
  first.send(JSON.stringify({ type: "publish", spaceId: "space-a", id: "request-1", kind: "connect_request", iv: "opaque-iv-2", ciphertext: "opaque-ciphertext-2" }));
  await ephemeralReceived;
  second.close();
  await new Promise(resolve => second.once("close", resolve));
  const rejoined = new WebSocket(`ws://127.0.0.1:${port}/v1/ws`);
  await new Promise(resolve => rejoined.once("open", resolve));
  const replayedIds = [];
  rejoined.on("message", raw => {
    const message = JSON.parse(raw.toString());
    if (message.type === "item") replayedIds.push(message.id);
  });
  rejoined.send(JSON.stringify(join));
  await new Promise(resolve => setTimeout(resolve, 150));
  assert.ok(replayedIds.includes("event-1"));
  assert.ok(!replayedIds.includes("request-1"));
  first.close(); rejoined.close(); denied.close();
});

test("replays a persistent opaque event after server restart", async () => {
  const restartPort = port + 1;
  const restartDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "private-canvas-relay-restart-"));
  let child = await startServer(restartPort, restartDirectory);
  try {
    const first = new WebSocket(`ws://127.0.0.1:${restartPort}/v1/ws`);
    await new Promise(resolve => first.once("open", resolve));
    const join = { type: "join", spaceId: "durable-space", accessProof: "durable-proof", deviceId: "first" };
    first.send(JSON.stringify(join));
    await waitFor(first, message => message.type === "presence");
    const ack = waitFor(first, message => message.type === "ack" && message.id === "durable-event");
    first.send(JSON.stringify({ type: "publish", spaceId: "durable-space", id: "durable-event", kind: "event", iv: "iv", ciphertext: "ciphertext" }));
    await ack;
    first.close();
    child.kill();
    await new Promise(resolve => child.once("exit", resolve));

    child = await startServer(restartPort, restartDirectory);
    const second = new WebSocket(`ws://127.0.0.1:${restartPort}/v1/ws`);
    await new Promise(resolve => second.once("open", resolve));
    const replay = waitFor(second, message => message.type === "item" && message.id === "durable-event");
    second.send(JSON.stringify({ ...join, deviceId: "second" }));
    assert.equal((await replay).ciphertext, "ciphertext");
    second.close();
  } finally {
    child?.kill();
    fs.rmSync(restartDirectory, { recursive: true, force: true });
  }
});

test("rejects a corrupt room log instead of replacing it", async () => {
  const corruptPort = port + 2;
  const corruptDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "private-canvas-relay-corrupt-"));
  const spaceId = "corrupt-space";
  const fileName = crypto.createHash("sha256").update(spaceId).digest("hex") + ".jsonl";
  const file = path.join(corruptDirectory, fileName);
  const original = "{not-json}\n";
  fs.writeFileSync(file, original);
  const child = await startServer(corruptPort, corruptDirectory);
  try {
    const socket = new WebSocket(`ws://127.0.0.1:${corruptPort}/v1/ws`);
    await new Promise(resolve => socket.once("open", resolve));
    const error = waitFor(socket, message => message.type === "error");
    socket.send(JSON.stringify({ type: "join", spaceId, accessProof: "new-proof", deviceId: "device" }));
    assert.equal((await error).message, "SPACE ACCESS DENIED");
    assert.equal(fs.readFileSync(file, "utf8"), original);
    socket.close();
  } finally {
    child.kill();
    fs.rmSync(corruptDirectory, { recursive: true, force: true });
  }
});
