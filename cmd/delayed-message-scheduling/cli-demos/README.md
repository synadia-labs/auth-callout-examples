# Delayed Message Scheduling — CLI Demo

| Field           | Value                                                    |
| --------------- | -------------------------------------------------------- |
| **Topic**       | Delayed Message Scheduling                               |
| **Date**        | 2026-04-02                                               |
| **NATS Server** | 2.12+ (single delayed), 2.14+ (cron, sampling, timezone) |
| **NATS CLI**    | 0.1.6+                                                   |
| **Go**          | 1.21+                                                    |

---

## Prerequisites

### Install

- nats-server 2.14+ — [install guide](https://docs.nats.io/running-a-nats-service/introduction/installation)
- nats CLI (natscli) — `go install github.com/nats-io/natscli/nats@latest`
- Go 1.21+ — [download](https://go.dev/dl/)
- nats.go client — `go get github.com/nats-io/nats.go@latest`

### Server Setup

```bash
nats-server -js
```

Start a NATS server with JetStream enabled. No config file needed for this demo.

---

## Core Property Under Test

The Message Scheduler delivers messages at a specified future time without any consumer-side polling or delay logic. Without the scheduler, you would need external cron, application timers, or the `NakWithDelay` workaround to achieve deferred delivery. This demo proves the server handles delivery timing autonomously.

---

## Steps

### Step 1: Create a schedule-enabled stream

Create a stream with `AllowSchedules` enabled. This is a prerequisite — scheduling headers are rejected on streams without this flag. Target subjects (`orders`, `reports.>`) must be in the same stream because the scheduler can only deliver within its own stream.

```bash
nats stream add SCHEDULES \
  --subjects="schedules.>,orders,reports.>" \
  --defaults \
  --allow-msg-ttl \
  --allow-schedules
```

Expected output:

```
Stream SCHEDULES was created

Information for Stream SCHEDULES created 2026-04-02T12:00:00Z

...
         Allow Msg TTL: true
        Allow Schedules: true
...
```

### Step 2: Verify the stream configuration

Confirm the scheduling flags are active.

```bash
nats stream info SCHEDULES --json | grep -E '"allow_(msg_ttl|msg_schedules)"'
```

Expected output:

```
  "allow_msg_ttl": true,
  "allow_schedules": true,
```

### Step 3: Subscribe to the target subject

The scheduler delivers messages directly into the stream, so a plain `nats sub orders` won't see them. Add `--stream` to consume via JetStream:

```bash
nats sub orders --stream SCHEDULES --new
```

Leave this running in a separate terminal. Expected output (initially):

```
12:00:00 Subscribing to subject orders for new messages
```

### Step 4: Publish a single delayed message

Schedule a message for 10 seconds in the future. Replace the timestamp with a time 10 seconds from now.

```bash
nats pub -J 'schedules.orders.demo1' \
  -H "Nats-Schedule: @at $(date -u -d '+10 seconds' +%Y-%m-%dT%H:%M:%SZ)" \
  -H "Nats-Schedule-TTL: 5m" \
  -H "Nats-Schedule-Target: orders" \
  '{"order_id": "abc-123", "action": "process"}'
```

Expected output (publish side):

```
Published 48 bytes to schedules.orders.demo1
```

After ~10 seconds, the subscriber from step 3 shows:

```
[#1] Received on "orders"
Nats-Scheduler: schedules.orders.demo1
Nats-Schedule-Next: purge
Nats-TTL: 5m

{"order_id": "abc-123", "action": "process"}
```

Note `Nats-Schedule-Next: purge` — the schedule subject is cleaned up after delivery.

### Step 5: Peek at the schedule message (run immediately)

Run this right after publishing — you have ~10 seconds before the message is delivered and purged.

```bash
nats stream get SCHEDULES --last-for="schedules.orders.demo1"
```

Expected output shows the stored schedule message with its headers (`Nats-Schedule`, `Nats-Schedule-Target`, `Nats-Schedule-TTL`). This confirms the message is sitting in the stream, waiting for the scheduled time.

### Step 6: Verify the schedule message was purged after delivery

After the delayed message fires, check per-subject message counts:

```bash
nats stream subjects SCHEDULES
```

Expected output shows the `schedules.orders.demo1` subject is gone — the server purged the schedule message after delivery, so no messages remain under that subject. The `orders` subject will have 1 message (the delivered payload), since target subjects share the same stream.

### Step 7: Publish a recurring hourly schedule

Create a cron-based schedule that fires every minute (for demo purposes, using `@every 15s`).

```bash
nats pub -J 'schedules.orders.recurring' \
  -H "Nats-Schedule: @every 15s" \
  -H "Nats-Schedule-TTL: 1m" \
  -H "Nats-Schedule-Target: orders" \
  '{"action": "check_pending"}'
```

Expected output (publish side):

```
Published 28 bytes to schedules.orders.recurring
```

The subscriber shows messages arriving every 15 seconds:

```
[#2] Received on "orders"
Nats-Scheduler: schedules.orders.recurring
Nats-Schedule-Next: 2026-04-02T12:01:15Z

{"action": "check_pending"}
```

### Step 8: Update an existing schedule

Overwrite the recurring schedule by publishing to the same subject with a different interval.

```bash
nats pub -J 'schedules.orders.recurring' \
  -H "Nats-Schedule: @every 30s" \
  -H "Nats-Schedule-TTL: 1m" \
  -H "Nats-Schedule-Target: orders" \
  '{"action": "check_pending_v2"}'
```

The subscriber now receives messages every 30 seconds instead of 15. This demonstrates that the last message on a schedule subject controls the schedule.

### Step 9: Clean up before sampling

Purge the recurring schedule from step 7 so its deliveries don't clutter the output.

```bash
nats stream purge SCHEDULES --subject="schedules.orders.recurring" -f
```

Expected output:

```
Purged 1 messages from Stream SCHEDULES matching subject schedules.orders.recurring
```

### Step 10: Subject sampling for data reduction

Simulate a high-frequency sensor by publishing several messages, then set up a sampler.

Start a continuous sensor publisher in the background:

```bash
# Simulate a high-frequency sensor (publishes every 200ms)
while true; do
  nats pub 'schedules.sensor.raw' "{\"temp\": $((RANDOM % 20 + 65)), \"ts\": \"$(date -Iseconds)\"}"
  sleep 0.2
done &
SENSOR_PID=$!
```

Set up a sampler that captures the latest reading every 10 seconds:

```bash
nats pub -J 'schedules.sensor.sampler' \
  -H "Nats-Schedule: @every 10s" \
  -H "Nats-Schedule-Source: schedules.sensor.raw" \
  -H "Nats-Schedule-Target: reports.sensor.sampled" \
  ""
```

Subscribe to the sampled subject in another terminal:

```bash
nats sub "reports.sensor.sampled" --stream SCHEDULES --new
```

Every 10 seconds, the latest sensor reading appears on the sampled subject — demonstrating data reduction from ~5 msgs/sec down to 1 every 10s.

When you're done, stop the sensor publisher:

```bash
kill $SENSOR_PID
```

### Step 11: Delete a schedule

Remove the sampler schedule by purging its subject from the stream.

```bash
nats stream purge SCHEDULES --subject="schedules.sensor.sampler" -f
```

Expected output:

```
Purged 1 messages from Stream SCHEDULES matching subject schedules.sensor.sampler
```

The recurring messages stop arriving.

---

## Go Demo

For a complete Go program that extends this CLI demo into a real-world pattern, see the [`go-demo/`](../go-demo/) directory.

```bash
cd ../go-demo
go run main.go
```

---

## Cleanup

```bash
nats stream delete SCHEDULES -f
```
