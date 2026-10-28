# NATS AuthCallout — Leaf Sentinel Example (Java)

## Overview

A **hub NATS server** with a **leaf node** connection. Both use the decentralized operator model.
The hub has `default_sentinel` configured. Leaf nodes and regular NKey clients connect using only
an NKey seed; the hub applies the sentinel and dispatches to `LeafSentinelAuthCalloutService`.

**Critical security feature:** The service performs **Ed25519 nonce signature verification** before
the KV whitelist lookup. When `default_sentinel` is active, the NATS server skips its native nonce-sig
check — this callout is the **sole enforcer of key possession** for all NKey connections.

```
Leaf Node (NKey seed)           Hub Server (port 4222 / leafnodes 7422)
     │                               │
     │── leaf connect (NKey sig) ───►│
     │                               │── applies default_sentinel ──►│
     │                               │── $SYS.REQ.USER.AUTH ─────────►│ LeafSentinelAuthCalloutService
     │                               │                     verify nonce sig
     │                               │                     lookup NKey in auth-nkeys
     │◄── leaf connected in APP1 ────│◄── signed JWT ─────────────────│

Regular NKey Client (port 4222)   same flow, kind = "Client"
Leaf Node (port 4223)             same flow, kind = "Leafnode"
```

## Prerequisites

- `nats-server` >= 2.11, `nsc` CLI, `nk` CLI, `nats` CLI
- Java 17+, Maven 3.8+

## Setup

```bash
bash generated/leaf-sentinel/java/setup.sh
```

Fill the `REPLACE_WITH_...` placeholders in:
- `nats-server-hub.conf` — sentinel JWT
- `nats-server-leaf.conf` — leaf NKey seed
- `config.properties` — service creds, signing key, account public keys

## Build
```bash
cd generated/leaf-sentinel/java && mvn package -q
# → target/authcallout-leaf-sentinel-1.0.0.jar
```

## Startup Sequence (order matters)

```bash
# Terminal 1 — Hub server
cp resolver.conf generated/leaf-sentinel/java/
nats-server -c generated/leaf-sentinel/java/nats-server-hub.conf

# Terminal 2 — AuthCallout service (MUST be running before leaf connects)
java -jar generated/leaf-sentinel/java/target/authcallout-leaf-sentinel-1.0.0.jar \
     service generated/leaf-sentinel/java/config.properties

# Terminal 3 — Leaf node
nats-server -c generated/leaf-sentinel/java/nats-server-leaf.conf
```

Service logs will show:
```
Auth SUCCESS — kind: Leafnode, identity: 'U...', account: 'A...'
```

## Testing NKey Clients Against Hub

```bash
# Generate a client NKey and add to whitelist
nk -gen user
nats kv put auth-nkeys <PUBLIC_KEY> "test-client" --creds creds/auth.creds -s nats://localhost:4222

# Connect with Java client
java -jar generated/leaf-sentinel/java/target/authcallout-leaf-sentinel-1.0.0.jar \
     client nats://localhost:4222 <SEED> test
```

## Troubleshooting

| Error | Fix |
|---|---|
| Leaf won't connect | Start the AuthCallout service BEFORE the leaf node |
| `Nonce signature verification failed` | NKey seed doesn't match public key in auth-nkeys |
| `NKey not authorized` | Public key not in `auth-nkeys` bucket |
| `not valid` on hub startup | `default_sentinel` JWT stale — regenerate with `nsc describe user -a AUTH -n sentinel --raw` |
| Port conflict on leaf | Leaf `port: 4223` must differ from hub `port: 4222` |
