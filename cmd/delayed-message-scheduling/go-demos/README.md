# Delayed Message Scheduling

Demonstrates NATS JetStream's Message Scheduler — server-side deferred delivery
without client-side polling or timers.

| Field           | Value  |
| --------------- | ------ |
| **NATS Server** | 2.14+  |
| **nats CLI**    | 0.1.6+ |
| **Go**          | 1.21+  |

## What it covers

- **One-shot delay** (`@at <RFC3339>`) — deliver a message at an exact future
  time
- **Recurring schedule** (`@every <duration>`) — repeat delivery on a fixed
  interval
- **Subject sampling** — periodically re-publish the latest value from a
  high-frequency subject (data reduction)
- **Schedule cancellation** — purge a schedule subject to stop delivery

## Run the Go demo

```bash
# From the repo root
task server:single
go run ./cmd/delayed-message-scheduling
```

## CLI walkthrough
