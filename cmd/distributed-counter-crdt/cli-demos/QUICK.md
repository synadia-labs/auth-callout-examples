# Distributed Counter CRDT — Quick Reference

## Setup

```bash
nats-server -js
```

## Steps

### 1. Create a counter-enabled stream

```bash
nats stream add COUNTER \
  --subjects="counter.>" \
  --defaults \
  --allow-counter
```

### 2. Verify counter config

```bash
nats stream info COUNTER --json | grep -E '"allow_(msg_counter|direct)"'
```

### 3. Increment a counter (PubAck returns the new total)

```bash
nats req counter.page.views "" -H "Nats-Incr:+1"
# {"stream":"COUNTER","seq":1,"val":"1"}
```

### 4. Large increment (arbitrary precision)

```bash
nats req counter.page.views "" -H "Nats-Incr:+999999999999999999"
```

### 5. Read the stored state

```bash
nats stream get COUNTER --last-for counter.page.views
```

### 6. Decrements and interleaved deltas

```bash
nats req counter.page.views "" -H "Nats-Incr:-100"
nats req counter.page.views "" -H "Nats-Incr:+50"
nats req counter.page.views "" -H "Nats-Incr:-10"
nats stream get COUNTER --last-for counter.page.views
```

### 7. Multiple independent subjects

```bash
nats req counter.orders.placed "" -H "Nats-Incr:+1"
nats req counter.orders.placed "" -H "Nats-Incr:+1"
nats stream get COUNTER --last-for counter.orders.placed
```

### 8. Plain publish is rejected

```bash
nats req counter.page.views "not a counter update"
```

### 9. Reset with matching negative delta

```bash
nats stream get COUNTER --last-for counter.page.views
nats req counter.page.views "" -H "Nats-Incr:-1000000000000000040"
nats stream get COUNTER --last-for counter.page.views
```

## Cleanup

```bash
nats stream rm COUNTER -f
```
