# NATS AuthCallout — Leaf Sentinel Example (Go)

## Overview

A **hub NATS server** with a **leaf node** connection. Both use the decentralized
operator model. The hub has `default_sentinel` configured. Leaf nodes and regular
NKey clients connect using only an NKey seed; the hub applies the sentinel and
dispatches to `LeafSentinelAuthCalloutService`.

**Critical security feature:** The service performs **Ed25519 nonce signature
verification** before the KV whitelist lookup. When `default_sentinel` is active,
the NATS server skips its native nonce-sig check — this callout is the **sole
enforcer of key possession** for all NKey connections.

```
Leaf Node (NKey seed)           Hub Server (port 4222 / leafnodes 7422)
     |                               |
     |-- leaf connect (NKey sig) --->|
     |                               |-- applies default_sentinel -->|
     |                               |-- $SYS.REQ.USER.AUTH ------->| LeafSentinelService
     |                               |                  verify nonce sig
     |                               |                  lookup NKey in auth-nkeys
     |<-- leaf connected in APP1 ----|<-- signed JWT ----------------|

Regular NKey Client (port 4222)   same flow, kind = "Client"
Leaf Node (port 4223)             same flow, kind = "Leafnode"
```

## Prerequisites

- `nats-server` >= 2.11, `nsc` CLI, `nk` CLI, `nats` CLI
- Go 1.21+

## Configuration

| Variable | Required | Default | Description |
|---|---|---|---|
| `SERVICE_CREDS` | **yes** | — | Path to auth user .creds file |
| `SIGNING_KEY_PATH` | **yes** | — | Path to signing NKey .nk file |
| `ISSUER_ACCOUNT` | **yes** | — | AUTH account public key |
| `DEFAULT_ACCOUNT` | **yes** | — | APP1 account public key |
| `NATS_URL` | no | `nats://localhost:4222` | Hub NATS URL |
| `NKEY_BUCKET` | no | `auth-nkeys` | NKey whitelist KV bucket |

## Setup

```bash
bash generated/leaf-sentinel/go/setup.sh
```

Follow printed instructions to fill in config placeholders and add leaf NKey to KV.

## Build

```bash
cd generated/leaf-sentinel/go
make build
```

## Startup Sequence (order matters)

```bash
# Terminal 1 — Hub server
cp resolver.conf generated/leaf-sentinel/go/
nats-server -c generated/leaf-sentinel/go/nats-server-hub.conf

# Terminal 2 — AuthCallout service (MUST be running before leaf connects)
SERVICE_CREDS=creds/auth.creds \
SIGNING_KEY_PATH=signing.nk \
ISSUER_ACCOUNT=<AUTH-pub> \
DEFAULT_ACCOUNT=<APP1-pub> \
./authcallout-leaf-sentinel service

# Terminal 3 — Leaf node
nats-server -c generated/leaf-sentinel/go/nats-server-leaf.conf
```

Service logs will show:
```
Auth SUCCESS -- kind: Leafnode, identity: 'U...', account: 'A...'
```

## Testing NKey Clients Against Hub

```bash
# Generate a client NKey and add to whitelist
nk -gen user
nats kv put auth-nkeys <PUBLIC_KEY> "test-client" --creds creds/auth.creds -s nats://localhost:4222

# Connect with Go client
./authcallout-leaf-sentinel client SUABC... my-label

# Or with nats CLI
nats pub test "hello" --nkey <seed-file> -s nats://localhost:4222
```

## Troubleshooting

| Error | Fix |
|---|---|
| Leaf won't connect | Start the AuthCallout service BEFORE the leaf node |
| `nonce signature verification failed` | NKey seed doesn't match public key in auth-nkeys |
| `NKey not authorized` | Public key not in `auth-nkeys` bucket |
| `not valid` on hub startup | `default_sentinel` JWT stale — regenerate with `nsc describe user -a AUTH -n sentinel --raw` |
| Port conflict on leaf | Leaf `port: 4223` must differ from hub `port: 4222` |
