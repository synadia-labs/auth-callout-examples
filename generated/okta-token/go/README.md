# NATS AuthCallout — Okta Token Introspection Example (Go)

## Overview

A centralized NATS AuthCallout service that validates a **JWT access token** via
Okta's token introspection endpoint. Clients connect using `nats.Token()`.

```
Client (access_token)       NATS Server (port 4222)
     |                            |
     |--- connect (token) ------->|
     |                            |--- $SYS.REQ.USER.AUTH ---> AuthCallout Service
     |                            |                             POST /oauth2/v1/introspect
     |                            |                             (Okta introspection)
     |                            |                    <-- { "active": true }
     |<--- connected in APP ------| <-- signed JWT ------------|
```

## Prerequisites

- `nats-server` >= 2.10, `nats` CLI, `nk` CLI
- Okta account with an API Services (M2M) or web application
- Go 1.21+

## Configuration

| Variable | Required | Default | Description |
|---|---|---|---|
| `SIGNING_KEY_SEED` | **yes** | — | Account NKey seed (`SA...`) |
| `OKTA_DOMAIN` | **yes** | — | e.g. `https://dev-XXXXX.okta.com` |
| `OKTA_CLIENT_ID` | **yes** | — | Okta application client ID |
| `OKTA_CLIENT_SECRET` | **yes** | — | Okta application client secret |
| `NATS_URL` | no | `nats://localhost:4222` | NATS server URL |
| `SERVICE_USER` | no | `auth` | Auth service username |
| `SERVICE_PASS` | no | `auth` | Auth service password |
| `DEFAULT_ACCOUNT` | no | `APP` | Target account for authenticated users |
| `OKTA_INTROSPECT_PATH` | no | `/oauth2/v1/introspect` | Introspection endpoint path |

## Build

```bash
cd generated/okta-token/go
make build
```

## Startup Sequence

```bash
# 1. Generate signing NKey
nk -gen account
# Copy A... into nats-server.conf -> auth_callout.issuer

# 2. Start NATS server
nats-server -c generated/okta-token/go/nats-server.conf

# 3. Start service
export SIGNING_KEY_SEED=SA...
export OKTA_DOMAIN=https://dev-XXXXX.okta.com
export OKTA_CLIENT_ID=...
export OKTA_CLIENT_SECRET=...
./authcallout-okta-token service
```

## Running the Client

Obtain an Okta access token via your login flow, then:

```bash
./authcallout-okta-token client eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...

# Or with the NATS CLI:
nats pub test "hello" --creds /dev/stdin <<< "token: <your-access-token>"
```

## Troubleshooting

| Error | Fix |
|---|---|
| `auth token required` | Client must connect with `nats.Token(accessToken)` |
| `token is inactive or invalid` | Token expired or revoked — get a fresh token |
| `okta introspect request` failed | Check `OKTA_DOMAIN`, `OKTA_CLIENT_ID`, `OKTA_CLIENT_SECRET` |
| `not authorized` on service start | Signing key mismatch — regenerate with `nk -gen account` |
