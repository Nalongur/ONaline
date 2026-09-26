# ONaline relay

The relay stores and forwards only opaque AES-GCM envelopes. It never receives a Space key or canvas plaintext. Persistent envelopes are kept as append-only JSONL files in `relay-server/data/` by default; set `RELAY_DATA_DIR` to move the data directory.

Run:

```powershell
npm.cmd install
npm.cmd start
```

The default port is `9876`; verify it with `Invoke-RestMethod http://127.0.0.1:9876/health`.

Android emulators can use `ws://10.0.2.2:9876/v1/ws`. Physical devices on the same LAN should use the computer's LAN address. Production deployment must use `wss://` behind a valid TLS certificate and persistent storage.
