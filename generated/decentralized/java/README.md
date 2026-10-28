# NATS AuthCallout — Decentralized Example (Java)

## Overview

Full decentralized NATS AuthCallout using the **nsc operator/account model** with a
NATS resolver. Supports three sub-scenarios in one service:

| | Scenario | Client credentials |
|---|---|---|
| A | Explicit sentinel | `sentinel.creds` + username/password |
| B | default_sentinel | username/password only (server auto-applies sentinel) |
| C | NKey challenge-response | NKey seed (default_sentinel required) |

```
Client                    NATS Server (resolver)        DecentralizedAuthCalloutService
  │                           │                                    │
  │─── CONNECT ──────────────►│                                    │
  │    (user/pass or NKey)    │── applies default_sentinel ───────►│
  │                           │── $SYS.REQ.USER.AUTH ─────────────►│
  │                           │                          validate credentials
  │                           │                          (KV or NKey whitelist)
  │                           │◄── signed AuthorizationResponse ───│
  │◄── connected in APP1 ─────│
```

## Prerequisites

- `nats-server` >= 2.11, `nats` CLI, `nsc` CLI, `nk` CLI
- Java 17+, Maven 3.8+

## Setup

```bash
bash generated/decentralized/java/setup.sh
```

This will:
1. Create nsc operator/accounts/users
2. Generate `.creds` files
3. Generate `resolver.conf`
4. Print the values to fill into `config.properties` and `nats-server.conf`

After running setup.sh, fill the `REPLACE_WITH_...` placeholders in both config files.

## Build
```bash
cd generated/decentralized/java && mvn package -q
# → target/authcallout-decentralized-1.0.0.jar
```

## Startup Sequence
```bash
# 1. Copy resolver.conf to the directory with nats-server.conf
cp resolver.conf generated/decentralized/java/

# 2. NATS server
nats-server -c generated/decentralized/java/nats-server.conf

# 3. Create KV buckets
nats kv add auth-users --creds creds/auth.creds -s nats://localhost:4222
nats kv add auth-nkeys --creds creds/auth.creds -s nats://localhost:4222

# 4. Add test user
HASH=$(echo -n "alice123" | sha256sum | awk '{print $1}')
nats kv put auth-users alice $HASH --creds creds/auth.creds -s nats://localhost:4222

# 5. AuthCallout service
java -jar generated/decentralized/java/target/authcallout-decentralized-1.0.0.jar \
     service generated/decentralized/java/config.properties
```

## Testing

```bash
# Scenario B — password client (default_sentinel)
java -jar generated/decentralized/java/target/authcallout-decentralized-1.0.0.jar \
     client password nats://localhost:4222 alice alice123 test

# Scenario C — NKey client
nk -gen user   # note seed (SU...) and public key (U...)
# Add public key to whitelist:
nats kv put auth-nkeys <PUBLIC_KEY> "my-client" --creds creds/auth.creds -s nats://localhost:4222
# Connect:
java -jar generated/decentralized/java/target/authcallout-decentralized-1.0.0.jar \
     client nkey nats://localhost:4222 <SEED> test
```

## Troubleshooting

| Error | Fix |
|---|---|
| `not valid` on startup | `default_sentinel` JWT doesn't match nsc store — regenerate with `nsc describe user -a AUTH -n sentinel --raw` |
| `NKey not authorized` | NKey public key not in `auth-nkeys` KV bucket |
| `Invalid password` | User not in `auth-users` bucket, or hash mismatch |
| Resolver errors | `resolver.conf` not in same directory as `nats-server.conf`, or JWTs out of date |
