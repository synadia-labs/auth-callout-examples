# Delayed Message Scheduling — Quick Reference

## Setup

```bash
nats-server -js
```

## Steps

### 1. Create a schedule-enabled stream

```bash
nats stream add SCHEDULES \
  --subjects="schedules.>,orders,reports.>" \
  --defaults \
  --allow-msg-ttl \
  --allow-schedules
```

### 2. Verify stream config

```bash
nats stream info SCHEDULES --json | grep -E '"allow_(msg_ttl|msg_schedules)"'
```

### 3. Subscribe to target subject (via JetStream)

```bash
nats sub orders --stream SCHEDULES --new
```

### 4. Publish a single delayed message (10 seconds from now)

```bash
nats pub -J 'schedules.orders.demo1' \
  -H "Nats-Schedule: @at $(date -u -d '+10 seconds' +%Y-%m-%dT%H:%M:%SZ)" \
  -H "Nats-Schedule-TTL: 5m" \
  -H "Nats-Schedule-Target: orders" \
  '{"order_id": "abc-123", "action": "process"}'
```

### 5. Peek at the schedule message (run immediately)

```bash
nats stream subjects SCHEDULES "schedules.orders.demo1"
```

### 6. Verify the schedule message was purged after delivery

```bash
nats stream subjects SCHEDULES
```

The `schedules.orders.demo1` subject should be gone — the server purged the
schedule message after delivery, so no messages remain under that subject. The
`orders` subject will have the delivered message (target subjects share the same
stream).

### 7. Publish a recurring schedule (every 15 seconds)

```bash
nats pub -J 'schedules.orders.recurring' \
  -H "Nats-Schedule: @every 5s" \
  -H "Nats-Schedule-TTL: 1m" \
  -H "Nats-Schedule-Target: orders" \
  '{"action": "check_pending"}'
```

### 8. Update an existing schedule (overwrite to every 30 seconds)

```bash
nats pub -J 'schedules.orders.recurring' \
  -H "Nats-Schedule: @every 10s" \
  -H "Nats-Schedule-TTL: 1m" \
  -H "Nats-Schedule-Target: orders" \
  '{"action": "check_pending_v2"}'
```

### 9. Clean up before sampling

```bash
nats stream purge SCHEDULES --subject="schedules.orders.recurring" -f
```

### 10. Set up subject sampling

```bash
# Start continuous sensor publisher in background
while true; do
  nats pub 'schedules.sensor.raw' "{\"temp\": $((RANDOM % 20 + 65)), \"ts\": \"$(date -Iseconds)\"}"
  sleep 0.2
done &
SENSOR_PID=$!

# Sample the latest reading every 10 seconds
nats pub -J 'schedules.sensor.sampler' \
  -H "Nats-Schedule: @every 10s" \
  -H "Nats-Schedule-Source: schedules.sensor.raw" \
  -H "Nats-Schedule-Target: reports.sensor.sampled" \
  ""

# Watch:
nats sub "reports.sensor.sampled" --stream SCHEDULES --new

# Stop the sensor publisher when done:
kill $SENSOR_PID 2>/dev/null || kill $(jobs -p) 2>/dev/null
```

### 11. Delete a schedule

```bash
nats stream purge SCHEDULES --subject="schedules.sensor.sampler" -f
```

## Cleanup

```bash
nats stream delete SCHEDULES -f
```
