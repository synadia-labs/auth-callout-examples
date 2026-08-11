# Distributed Counter CRDT

Demonstrates NATS JetStream's distributed counter — a stream that accepts signed
increments via the `Nats-Incr` header and converges across clusters, super
clusters, and sources without coordination.

| Field           | Value  |
| --------------- | ------ |
| **NATS Server** | 2.12+  |
| **nats CLI**    | 0.3+   |
| **API Level**   | 2      |

## What it covers

- **Stream opt-in** (`--allow-counter`) — turning a stream into a counter
- **Increment header** (`Nats-Incr`) — signed delta encoding (`+1`, `-5`, `+1000`)
- **PubAck `val`** — the post-increment total is returned in the ack, no separate read
- **Order-independence** — concurrent increments commute to the same total
- **Arbitrary precision** — values can exceed 2^64 (stored as `big.Int`)
- **Rejection rules** — messages without `Nats-Incr` or with conflicting headers are rejected
- **Reset pattern** — publish a matching negative delta to reset a replicated counter

## Prerequisites

Start a local JetStream-enabled server:

```bash
nats-server -js
```

Or run with `task server:single` from the repo root.

## CLI walkthrough

### 1. Create a counter-enabled stream

A counter stream opts in with `--allow-counter`. Once enabled, the flag cannot
be turned off, and only messages carrying `Nats-Incr` are accepted.

```bash
nats stream add COUNTER \
  --subjects="counter.>" \
  --defaults \
  --allow-counter
```

Expected output:

```
Stream COUNTER was created

Information for Stream COUNTER ...

                Subjects: counter.>
               Retention: Limits
              Direct Get: true
         Allows Counters: true
```

### 2. Verify the stream is counter-enabled

Use `nats stream info` to confirm `AllowMsgCounter` is set.

```bash
nats stream info COUNTER --json | grep -E '"allow_(msg_counter|direct)"'
```

Expected output:

```
    "allow_direct": true,
    "allow_msg_counter": true
```

### 3. Increment a counter

Every write to a counter subject must carry `Nats-Incr` with a signed integer.
The PubAck includes the new total in its `val` field.

```bash
nats req counter.page.views "" -H "Nats-Incr:+1" --raw
```

Expected output:

```
{"stream":"COUNTER","seq":1,"val":"1"}
```

### 4. Add a larger increment

Counters use arbitrary precision, so any signed integer works — no overflow.

```bash
nats req counter.page.views "" -H "Nats-Incr:+100" --raw
nats req counter.page.views "" -H "Nats-Incr:+999999999999999999" --raw
```

Expected output:

```
{"stream":"COUNTER","seq":2,"val":"101"}
{"stream":"COUNTER","seq":3,"val":"1000000000000000100"}
```

Note: values can scale well beyond 2^63 — no overflow is possible since the counter uses arbitrary precision (`big.Int` internally).

### 5. Read the current counter value

The last message on the subject holds the running total in its body.

```bash
nats stream get COUNTER --last-for "counter.page.views"
```

Expected output:

```
Item: COUNTER#3 received ... on Subject counter.page.views

Headers:
  Nats-Incr: +999999999999999999

{"val":"1000000000000000100"}
```

### 6. Decrement the counter

Negative deltas are how decrements work. Order does not matter — commutativity
means interleaved +s and -s all converge to the same answer.

```bash
nats req counter.page.views "" -H "Nats-Incr:-100" --raw
nats req counter.page.views "" -H "Nats-Incr:+50" --raw
nats req counter.page.views "" -H "Nats-Incr:-10" --raw
```

Expected output (final total = `1000000000000000040`):

```
{"stream":"COUNTER","seq":4,"val":"1000000000000000000"}
{"stream":"COUNTER","seq":5,"val":"1000000000000000050"}
{"stream":"COUNTER","seq":6,"val":"1000000000000000040"}
```

### 7. Verify multiple subjects are independent counters

Each subject in a counter stream is its own counter — they don't share state.

```bash
nats req counter.orders.placed "" -H "Nats-Incr:+1" --raw
nats req counter.orders.placed "" -H "Nats-Incr:+1" --raw
nats req counter.orders.placed "" -H "Nats-Incr:+1" --raw

nats stream get COUNTER --last-for "counter.orders.placed"
```

Expected output (orders counter is 3, views counter is untouched):

```
{"stream":"COUNTER","seq":7,"val":"1"}
{"stream":"COUNTER","seq":8,"val":"2"}
{"stream":"COUNTER","seq":9,"val":"3"}

Item: COUNTER#9 received ... on Subject counter.orders.placed

Headers:
  Nats-Incr: +1

{"val":"3"}
```

### 8. A plain publish is rejected

A counter stream only accepts messages with `Nats-Incr`. Any normal publish
is rejected by the server — this is what makes the feature safe against
accidental writes.

```bash
nats req counter.page.views "not a counter update" --raw
```

Expected output:

```
{"error":{"code":400,"err_code":10169,"description":"message counter increment is missing"},"stream":"COUNTER","seq":0}
```

### 9. Reset a counter with a matching negative delta

Subject purge doesn't replicate across sources, so the safe reset pattern is to
publish a negative delta equal to the current total. Note: this read-negate-publish
sequence has a race window — a concurrent writer between the read and the publish
will cause the counter to drift from zero.

```bash
# Get current value
CURRENT=$(nats stream get COUNTER --last-for "counter.page.views" 2>&1 \
  | grep -oE '"val":"[^"]+"' | tail -1 | cut -d'"' -f4)
echo "Current: $CURRENT"

# Publish the negation
nats req counter.page.views "" -H "Nats-Incr:-$CURRENT" --raw

# Confirm reset to zero
nats stream get COUNTER --last-for "counter.page.views"
```

Expected output:

```
Current: 1000000000000000040
{"stream":"COUNTER","seq":10,"val":"0"}

Item: COUNTER#10 received ... on Subject counter.page.views

Headers:
  Nats-Incr: -1000000000000000040

{"val":"0"}
```

## Cleanup

```bash
nats stream rm COUNTER -f
```

## Why this demo is representative

The core property being tested is **commutativity** — a counter stream must
converge to the same value regardless of increment order. Step 6 demonstrates
this by interleaving positive and negative deltas: the final total is the
algebraic sum, not the last value seen. Against plain pub/sub, step 8 would
silently store random payloads instead of rejecting them — the output would be
visibly different.
