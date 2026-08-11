# Distributed Counter CRDT — Embedded Demo

NATS server embedded directly in each app binary (`nats-server/v2`). One container per region.

Run with: `task compose`

## Debugging a single service

To debug one service locally while the rest run in Docker:

1. Run all services except the target: `task compose:except-<service>`  
   e.g. `task compose:except-america`

2. Start the target service with the env vars from `docker-compose.yml` (e.g. `LEAF_REMOTE_URL`, `STORE_DIR`).

The local process connects to the Docker cluster via the exposed NATS leaf ports (`localhost:7422` for global-connected nodes, `localhost:7423` for europe-connected nodes). Non-asia services use `HTTP_ADDR=:8089` to avoid conflicting with asia's host port mapping (asia owns `localhost:8080` on the host).

## Demo tasks

| Task | Description |
|------|-------------|
| `task compose` | Start all services |
| `task compose:down` | Stop and remove all containers and volumes |
| `task compose:except-<service>` | Start all except one (for local debugging) |
| `task node:isolate NODE=<spain\|france\|england>` | Cut a European leaf node's NATS connection (HTTP stays up, writes accumulate locally) |
| `task node:rejoin NODE=<spain\|france\|england>` | Restore the connection (accumulated writes converge immediately) |
| `task europe:down` | Stop Europe container (Spain/France/England accumulate in isolation) |
| `task europe:up` | Restart Europe (sources catch up, Global snaps to the correct total) |

## Topology

```
global  (8081)
├── asia    (8080)
├── america (8082)
└── europe  (8083)
    ├── spain   (8084)
    ├── france  (8085)
    └── england (8086)
```

Global aggregates from asia, america, and europe. Europe aggregates from spain, france, and england.

---

## Code reference

### Node types

**Leaf nodes** (america, asia, england, france, spain) connect outward to a hub via `LEAF_REMOTE_URL`, own their own counter stream, and expose hit and zero endpoints. **europe** is both — it connects to global as a leaf and simultaneously acts as a hub for spain, france, and england. **global** is the root hub only — no `LEAF_REMOTE_URL`. Both europe and global are read-only from the HTTP perspective: they source or mirror remote streams locally and only expose counter-display endpoints.

What follows is a summary of every function organized by role.

---

### Shared helpers — all nodes

#### `getEnv(key, fallback string) string`
Returns the environment variable `key` if set, otherwise returns `fallback`. Used for `HTTP_ADDR` and `STORE_DIR`. Present in all nodes except global, which uses `os.Getenv` inline.

#### `valFromMsg(data []byte) string`
Parses the JSON body of a NATS message and extracts the `"val"` field as a string. Returns `"0"` on any error or empty value. The `val` field is the accumulated counter total NATS computes by summing all `Nats-Incr` deltas published to the stream.

---

### Leaf nodes: america, asia

Both share identical code. Only the constants (`subject`, `streamName`, server name, and JetStream domain) differ.

#### `main()`
Reads `STORE_DIR` (default `/data`) and `LEAF_REMOTE_URL` (required) from the environment. Starts an embedded NATS leaf node server with JetStream enabled. Connects a NATS client with a retry loop, subscribes to the node's hit subject for debug logging, then creates the node's counter stream (`COUNTER_<NODE>`) via another retry loop. Registers four HTTP routes and starts the server on `HTTP_ADDR` (default `:8080`).

#### `(a *app) readVal() string`
Fetches the most recent message on the node's hit subject directly from the stream using `GetLastMsgForSubject` with a 300ms timeout. Returns the current counter value, or `"0"` on error. Used by `handleZero` to determine the current total before negating it.

#### `(a *app) handleCounters(w, r)` — `GET /counters`
SSE endpoint for the browser. Creates an ordered consumer starting from the last message (`DeliverLastPolicy`), then loops forever forwarding each new stream message to the browser as a Datastar signal patch on the `hits` signal.

#### `(a *app) handleHit(w, r)` — `POST /hit/{node}/{amount}`
Parses `amount` from the path and publishes a NATS message with header `Nats-Incr: +<amount>` to atomically increment the counter. The publish acknowledgment's `ack.Value` contains the new total, which is sent back as a Datastar signal patch.

#### `(a *app) handleZero(w, r)` — `POST /zero/{node}`
Calls `readVal()` to get the current total, then publishes `Nats-Incr: -<current>` to bring the counter to zero. Sends the result back as a Datastar signal patch. Exits early if the counter is already `0`.

---

### European leaf nodes: spain, france, england

Same as america/asia plus two additional routes for live network partition simulation. The `app` struct carries a `sync.Mutex` and `partitioned bool` to guard concurrent reload calls.

#### `main()`
Identical to america/asia except registers **six** HTTP routes: the four above plus `POST /partition` and `POST /rejoin`.

#### `(a *app) readVal() string`
Same as america/asia.

#### `(a *app) handleCounters(w, r)` — `GET /counters`
Same as america/asia.

#### `(a *app) handleHit(w, r)` — `POST /hit/{node}/{amount}`
Same as america/asia.

#### `(a *app) handleZero(w, r)` — `POST /zero/{node}`
Same as america/asia.

#### `(a *app) handlePartition(w, r)` — `POST /partition`
Calls `ns.ReloadOptions` with the leaf remote's `Disabled` flag set to `true`, severing the NATS connection to Europe while the HTTP server stays up. Writes accumulate in the local JetStream. Called by `task node:isolate NODE=<node>`.

#### `(a *app) handleRejoin(w, r)` — `POST /rejoin`
Calls `ns.ReloadOptions` with `Disabled: false`, re-enabling the leaf connection. The local stream immediately syncs accumulated writes upstream; the global total converges. Called by `task node:rejoin NODE=<node>`.

---

### europe

#### `main()`
Two-phase stream setup. Phase 1: creates `COUNTER_EUROPE` as a sourced stream pulling from `COUNTER_SPAIN`, `COUNTER_FRANCE`, and `COUNTER_ENGLAND` (each from their respective domains), remapping all subjects to `count.europe.hits`. Phase 2: creates three local mirror streams (`COUNTER_FRANCE_VIEW`, `COUNTER_SPAIN_VIEW`, `COUNTER_ENGLAND_VIEW`) so global can read per-sub-region values without reaching back to the leaf nodes. Each mirror has a 15-second deadline and logs a warning (but continues) on failure. Only `GET /counters` is registered — no hit or zero routes.

#### `(a *app) handleCounters(w, r)` — `GET /counters`
Same pattern as leaf nodes, but streams the european aggregate total on `count.europe.hits`.

---

### global

#### `main()`
Starts the root NATS hub server listening for incoming leaf connections on port 7422 — no `LEAF_REMOTE_URL`. Subscribes to `count.*.hits` with a wildcard for debug logging. Creates `COUNTER_GLOBAL` sourcing from `COUNTER_AMERICA`, `COUNTER_ASIA`, and `COUNTER_EUROPE`, remapping all to `count.global.hits`. Then creates six local mirror view streams — one per top-level region (america, asia, europe) and three for europe's sub-regions pulled via europe's view streams. Each view stream has a 15-second deadline; failures are logged and skipped. Registers `GET /counters` and `GET /breakdown`.

#### `(a *app) handleCounters(w, r)` — `GET /counters`
Same pattern as leaf nodes, but streams the global aggregate total on `count.global.hits` using the `globalHits` signal.

#### `(a *app) handleBreakdown(w, r)` — `GET /breakdown`
Fans out one goroutine per region. Each goroutine creates its own ordered consumer on a local view stream and pushes updates onto a shared channel. The main loop selects from that channel and `ctx.Done()`, forwarding each update as a Datastar signal patch keyed by region signal name (e.g. `americaHits`, `spainHits`). All six region counters stream to the browser over a single SSE connection.
