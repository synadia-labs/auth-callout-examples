# Async Stream Flush — KubeCon Benchmarks

Shell and PowerShell scripts that run `nats bench` across 8 message sizes and 3
batch sizes, producing output that can be loaded into the HTML visualizers.

## R1 (single-replica, sync vs async persist mode)

- Start the cluster: `task server:super`
- Wait for all servers and leafnodes to be healthy: `task server:status`
- Run the bench: `./bench-r1.sh > output-r1.txt` (or
  `.\bench-r1.ps1 > output-r1.txt`)
- Open `r1.html` in a browser and load `output-r1.txt`

## R3 (3-replica, NATS 2.11 vs 2.12)

- Start with 2.11: `task server:super VERSION=2.11.x`
- Wait for all servers and leafnodes to be healthy: `task server:status`
- Run the bench: `./bench-r3.sh > output-r3.txt`
- Stop and wipe: `task server:stop`
- Start with 2.12: `task server:super VERSION=2.12.x`
- Wait for all servers and leafnodes to be healthy: `task server:status`
- **Append** to the same file: `./bench-r3.sh >> output-r3.txt`
- Open `r3.html` in a browser and load `output-r3.txt`

## What to Expect

### R1 — Sync vs. Async Flush

Async flush outperforms sync flush — the gap is most visible at batch 1, where
sync must complete a full `fdatasync` before every ack. Async acks from memory
and flushes in the background, removing disk latency from the critical path.

- **Batching narrows the gap.** At batch 100 or 500, sync flush coalesces many
  writes per `fdatasync` and recovers significant throughput. The async
  advantage is still there, but less dramatic than at batch 1.
- **Large messages shift the bottleneck.** Above 8 KB, network overhead starts
  to dominate and both modes converge.
- **The tradeoff is real.** Async acks before data hits disk, leaving a small
  durability window on crash. It's configurable per-stream, so you can apply it
  selectively where the tradeoff makes sense.
- **Results are environment-dependent.** A laptop or virtualized environment
  will compress the absolute numbers compared to bare metal with fast NVMe. The
  relative differences between modes still hold.

### R3 — NATS 2.11 vs. 2.12

In a 3-replica cluster, quorum round-trips dominate over local disk I/O — so
flush mode isn't the story here. The focus is on how NATS 2.12 improves
replication throughput compared to 2.11.

- **Batching still matters a lot.** At batch 1, every publish waits for a full
  quorum ack. Pipelining at batch 100 or 500 hides that latency and throughput
  climbs significantly.
- **The version delta may be subtle in constrained environments.** On bare metal
  the 2.12 improvements are more pronounced; on a laptop or VM the numbers will
  be closer together, but the trend should still be visible.
- **Results are environment-dependent.** Replication adds network round-trips
  between local processes here rather than real nodes, so absolute numbers will
  be lower than a real multi-node deployment.

---

## Observed Results

### R1 — Sync vs. Async Flush

**Batch 1:** Sync and async are nearly identical across all message sizes
(~7–12K msgs/sec). When sending one message at a time and waiting for an ack,
flush mode is irrelevant.

**Batch 100 & 500:** Async generally wins at small-to-mid sizes (128B–2KB),
often by 10–20%. At large sizes (8KB–16KB) the gap narrows or reverses — both
modes struggle similarly when messages themselves are heavy.

**Takeaway:** Async flush is faster when batching small messages. At large
message sizes, flush mode stops mattering because the bottleneck shifts to
bandwidth, not round-trips.

### R3 — NATS 2.11.11 vs. 2.12.2

**Batch 1:** Both versions are slow and nearly identical (~3–5K msgs/sec).
Single-message publishing is a latency-bound ceiling regardless of version.

**Batch 100 & 500:** 2.12.2 is consistently faster across every message size,
typically 10–20% more throughput. The gap is most visible at small sizes
(128B–512B) and stays meaningful through 4KB. At 8KB+ both versions converge as
bandwidth becomes the bottleneck.

**Takeaway:** 2.12.2 is a clear improvement for async/batched publishing.
Single-message publishing is unchanged.

---

## Script Details

### bench-r1.sh — Single Replica, Sync vs. Async Flush

Benchmarks two streams across 8 message sizes (128 B → 16 KB):

- **`sync`** — acks after `fdatasync` (default persist mode)
- **`async`** — acks after write to memory buffer; flushes in background

Each stream is tested at three batch sizes:

- **Batch 1** — `pub sync`, one in-flight at a time. Worst-case baseline; every
  ack is gated on whatever the server must do before responding.
- **Batch 100** — `pub async`, 100 in-flight. Client pipelining lets the server
  coalesce writes, recovering significant throughput.
- **Batch 500** — `pub async`, 500 in-flight. More coalescing, but gains taper
  at larger message sizes where bandwidth becomes the ceiling.

**What it shows:** The throughput delta between synchronous and asynchronous
server-side flushing, and how client-side batching interacts with each mode.

### bench-r3.sh — Three Replica Cluster

Benchmarks a single R3 stream across 8 message sizes (128 B → 16 KB).
Auto-discovers and connects directly to the Raft leader to avoid an extra
network hop. Server version is prepended to each label so two runs (e.g. 2.11
then 2.12 appended to the same file) are distinguishable in the visualizer.

- **Batch 1** — `pub sync`, one in-flight at a time. Isolates the raw
  per-message quorum round-trip cost.
- **Batch 100** — 100 in-flight. Pipelining hides replication latency; large
  gains over batch 1.
- **Batch 500** — 500 in-flight. Gains over batch 100 narrow as Raft log and
  network throughput become the ceiling.

**What it shows:** How client-side batching affects throughput when replication
latency is the bottleneck, and how that changes across NATS server versions.

---

## Real Environment Differences

These benchmarks run everything on one laptop, so the numbers are modest. In
production, each server gets its own machine, its own disk, and real network
cables between them — which means the gaps between sync and async, and between
2.11 and 2.12, would be noticeably larger. The winners are the same; the
margins are bigger.
