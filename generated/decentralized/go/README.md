# NATS AuthCallout — Decentralized Example (Go)

## Overview

A decentralized NATS AuthCallout service using the **nsc operator/accounts model**.
NKey clients connect with challenge-response signing; the service looks up the NKey
public key in a KV whitelist and issues a signed JWT with `IssuerAccount` set.

```
NKey Client                 NATS Server (port 4222)
     |                            |
     |--- connect (NKey sig) ---->|--- applies default_sentinel (optional)
     |                            |--- $SYS.REQ.USER.AUTH ---> AuthCallout Service
     |                            |                             KV lookup NKey
     |                            |                    <-- signed JWT (IssuerAccount=AUTH)
     |<--- connected in APP1 -----|

Password Client (user/pass)  same flow, routes to KV password handler
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
| `NATS_URL` | no | `nats://localhost:4222` | NATS server URL |
| `KV_BUCKET` | no | `auth-users` | Password users KV bucket |
| `NKEY_BUCKET` | no | `auth-nkeys` | NKey whitelist KV bucket |

## Setup

```bash
bash generated/decentralized/go/setup.sh
```

Follow the printed instructions to fill in `nats-server.conf` placeholders.

## Build

```bash
cd generated/decentralized/go
make build
```

## Startup Sequence

```bash
# 1. Start NATS server (resolver.conf must be present)
cp resolver.conf generated/decentralized/go/
nats-server -c generated/decentralized/go/nats-server.conf

# 2. Create KV buckets (first run only)
nats kv add auth-users --creds creds/auth.creds -s nats://localhost:4222
nats kv add auth-nkeys --creds creds/auth.creds -s nats://localhost:4222

# 3. Start service
SERVICE_CREDS=creds/auth.creds \
SIGNING_KEY_PATH=signing.nk \
ISSUER_ACCOUNT=<AUTH-pub> \
DEFAULT_ACCOUNT=<APP1-pub> \
./authcallout-decentralized service
```

## Running the Client

```bash
# NKey client
./authcallout-decentralized client SUABC...   # user NKey seed
make run-client SEED=SUABC...
```

## Testing with NATS CLI

```bash
# Add NKey to whitelist first
nk -gen user   # note public key
nats kv put auth-nkeys <PUBLIC_KEY> "my-client" --creds creds/auth.creds -s nats://localhost:4222

# Connect with nats CLI using NKey
nats pub test "hello" --nkey <SEED_FILE> -s nats://localhost:4222
```

## Troubleshooting

| Error | Fix |
|---|---|
| `NKey not authorized` | Public key not in `auth-nkeys` bucket |
| `user not found` | Username not in `auth-users` bucket |
| `open NKey KV bucket` | Run `nats kv add auth-nkeys ...` |
| `not valid` | Resolver hasn't loaded account JWT — run `kill -HUP <nats-server-pid>` |
| `IssuerAccount` error | `ISSUER_ACCOUNT` must be the AUTH account public key (not APP1) |
