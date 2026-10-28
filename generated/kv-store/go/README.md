# NATS AuthCallout — KV Store Example (Go)

## Overview

A centralized NATS AuthCallout service that validates username/password credentials
against a **NATS KV bucket**. Passwords are stored as SHA-256 hex strings.

```
Client (user/pass)          NATS Server (port 4222)
     |                            |
     |--- connect (user/pass) --->|
     |                            |--- $SYS.REQ.USER.AUTH ---> AuthCallout Service
     |                            |                             lookup user in KV
     |                            |                             compare SHA-256(pass)
     |<--- connected in APP ------| <-- signed JWT ------------|
```

## Prerequisites

- `nats-server` >= 2.10
- `nats` CLI
- `nk` CLI (`go install github.com/nats-io/nkeys/nk@latest`)
- Go 1.21+

## Configuration

| Variable | Required | Default | Description |
|---|---|---|---|
| `SIGNING_KEY_SEED` | **yes** | — | Account NKey seed (`SA...`) |
| `NATS_URL` | no | `nats://localhost:4222` | NATS server URL |
| `SERVICE_USER` | no | `auth` | Auth service username |
| `SERVICE_PASS` | no | `auth` | Auth service password |
| `DEFAULT_ACCOUNT` | no | `APP` | Target account for authenticated users |
| `KV_BUCKET` | no | `auth-users` | KV bucket holding credentials |

## Build

```bash
cd generated/kv-store/go
make build
# OR: go mod tidy && go build -o authcallout-kv-store .
```

## Startup Sequence

```bash
# 1. Generate signing NKey
nk -gen account
# Copy the public key (A...) into nats-server.conf -> auth_callout.issuer
# Copy the seed (SA...) -> use as SIGNING_KEY_SEED env var

# 2. Start NATS server
nats-server -c generated/kv-store/go/nats-server.conf

# 3. Create KV bucket and seed test users
bash generated/kv-store/go/setup.sh

# 4. Start AuthCallout service
SIGNING_KEY_SEED=SA... ./authcallout-kv-store service
```

## Running the Service

```bash
SIGNING_KEY_SEED=SA... ./authcallout-kv-store service
```

Expected output:
```
Connected to NATS at nats://localhost:4222
Using KV bucket "auth-users", default account "APP"
AuthCallout service ready -- waiting for auth requests...
Auth SUCCESS -- user: "alice", account: "APP"
```

## Running the Client

```bash
./authcallout-kv-store client alice alice123
./authcallout-kv-store client bob bobpass
```

## Testing with NATS CLI

```bash
# Success
nats pub test "hello" --user alice --password alice123 -s nats://localhost:4222

# Failure -- wrong password
nats pub test "hello" --user alice --password wrong -s nats://localhost:4222

# Add a new user
nats kv put auth-users carol "$(echo -n "carolpass" | sha256sum | awk '{print $1}')" \
    --user auth --password auth -s nats://localhost:4222
nats pub test "hi" --user carol --password carolpass -s nats://localhost:4222
```

## Troubleshooting

| Error | Fix |
|---|---|
| `not authorized` on service startup | Signing key mismatch — regenerate with `nk -gen account` and update `nats-server.conf` + `SIGNING_KEY_SEED` |
| `open KV bucket "auth-users"` | Run `bash setup.sh` to create the bucket before starting the service |
| `user not found` | Add user: `nats kv put auth-users <user> <sha256hash>` |
| `invalid password` | Recompute: `echo -n "password" | sha256sum` |
| `required env var SIGNING_KEY_SEED not set` | Export `SIGNING_KEY_SEED=SA...` before running |
