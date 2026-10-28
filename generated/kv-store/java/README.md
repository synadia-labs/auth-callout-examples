# NATS AuthCallout — KV Store Example (Java)

## Overview

This example demonstrates NATS AuthCallout with a **NATS KV Store** password backend.
When a client connects with a username and password, the NATS server delegates credential
validation to this AuthCallout service. The service looks up the username in a NATS KV
bucket and compares SHA-256(password) against the stored hash. On success it issues a
signed JWT placing the user into the `APP` account.

```
Client (alice/alice123)
    │
    ▼  CONNECT
NATS Server (port 4222)
    │
    ▼  $SYS.REQ.USER.AUTH
AuthCallout Service (AUTH account)
    │
    ▼  kv.get("alice") → compare SHA-256
NATS KV Bucket: auth-users
    │
    ▼  signed AuthorizationResponse JWT
NATS Server → Client connected in APP account
```

## Prerequisites

- `nats-server` >= 2.10
- `nats` CLI (`brew install nats-io/nats-tools/nats`)
- `nk` CLI for NKey generation (`brew install nats-io/nats-tools/nk`)
- Java 17+
- Maven 3.8+

## Configuration

Two placeholders must be filled before running:

### 1. Generate a signing NKey pair

```bash
nk -gen account
```

Output example:
```
SAAOAX42M4RNHBXZZ6NHYAIOVH7YFRRXED3TGKZIYK2DMF2BBJFER2VIAY   ← seed (SA...)
ABJMLE5LIGE3OIUO5IX2USGWSELK3XKHL6FAV7WOCRDD7E2WQNWL67FY     ← public key (A...)
```

### 2. Fill in the placeholders

In **`nats-server.conf`**, replace:
```
issuer: "REPLACE_WITH_AUTH_ACCOUNT_SIGNING_KEY_PUBLIC"
```
with the public key (`A...`).

In **`config.properties`**, replace:
```
auth.signing.key.seed=REPLACE_WITH_SA_SEED
```
with the seed (`SA...`).

## Build

```bash
cd generated/kv-store/java
mvn package -q
```

Produces: `target/authcallout-kv-store-1.0.0.jar`

## Startup Sequence

Run each step in a separate terminal tab.

### Step 1 — Start the NATS server

```bash
nats-server -c generated/kv-store/java/nats-server.conf
```

### Step 2 — Create KV bucket and test users

```bash
bash generated/kv-store/java/setup.sh
```

### Step 3 — Start the AuthCallout service

```bash
java -jar generated/kv-store/java/target/authcallout-kv-store-1.0.0.jar \
     service generated/kv-store/java/config.properties
```

Expected output:
```
HH:mm:ss [main] INFO  io.example.authcallout.ServiceRunner - Connecting to NATS at nats://localhost:4222 as 'auth'
HH:mm:ss [main] INFO  io.example.authcallout.KvStoreAuthHandler - KvStoreAuthHandler ready — bucket: auth-users
HH:mm:ss [main] INFO  io.example.authcallout.AuthCalloutService - AuthCalloutService started — listening on $SYS.REQ.USER.AUTH
HH:mm:ss [main] INFO  io.example.authcallout.ServiceRunner - AuthCallout service running. Press Ctrl+C to stop.
```

## Testing

### With the Java client

```bash
# Successful auth
java -jar generated/kv-store/java/target/authcallout-kv-store-1.0.0.jar \
     client nats://localhost:4222 alice alice123 test

# Wrong password — should fail with "Authentication failed"
java -jar generated/kv-store/java/target/authcallout-kv-store-1.0.0.jar \
     client nats://localhost:4222 alice wrongpassword test
```

### With the NATS CLI

```bash
# Success
nats pub test "hello world" --user alice --password alice123 -s nats://localhost:4222

# Wrong password
nats pub test "hello world" --user alice --password wrongpassword -s nats://localhost:4222

# Unknown user
nats pub test "hello world" --user nobody --password anything -s nats://localhost:4222

# Subscribe in another terminal to see messages
nats sub test --user alice --password alice123 -s nats://localhost:4222
```

### Add more users

```bash
# SHA-256 hash on macOS (use sha256sum on Linux)
HASH=$(echo -n "mynewpassword" | shasum -a 256 | awk '{print $1}')
nats kv put auth-users charlie "$HASH" --user auth --password auth -s nats://localhost:4222
```

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `nats: error: nats: Authorization Violation` | Wrong password, or signing key mismatch | Verify `issuer` in `nats-server.conf` matches the `A...` key from `auth.signing.key.seed` in `config.properties` |
| `KV bucket not found` | `setup.sh` not run yet | Run `bash setup.sh` with the server running |
| `connection refused` | NATS server not started | Start server first |
| `Required config key missing: auth.signing.key.seed` | Placeholder not replaced in `config.properties` | Run `nk -gen account` and fill in the seed |
