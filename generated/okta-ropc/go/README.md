# NATS AuthCallout — Okta ROPC Example (Go)

## Overview

A centralized NATS AuthCallout service that validates username/password credentials
via the **Okta Resource Owner Password Credentials (ROPC)** OAuth2 flow.

```
Client (user/pass)          NATS Server (port 4222)
     |                            |
     |--- connect (user/pass) --->|
     |                            |--- $SYS.REQ.USER.AUTH ---> AuthCallout Service
     |                            |                             POST /oauth2/v1/token
     |                            |                             (Okta ROPC)
     |                            |                    <-- access_token (success)
     |<--- connected in APP ------| <-- signed JWT ------------|
```

## Prerequisites

- `nats-server` >= 2.10, `nats` CLI, `nk` CLI
- Okta account with ROPC-enabled application
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
| `OKTA_SCOPES` | no | `openid profile email` | OAuth2 scopes |
| `OKTA_TOKEN_PATH` | no | `/oauth2/v1/token` | Okta token endpoint path |

## Okta Setup

1. Create an application (type: **Web** or **Native**)
2. Enable the **Resource Owner Password** grant type
3. Note the Client ID and Client Secret

> Note: Okta deprecated the ROPC flow for new orgs. For production use, prefer the `okta-token` (introspection) scenario.

## Build

```bash
cd generated/okta-ropc/go
make build
```

## Startup Sequence

```bash
# 1. Generate signing NKey
nk -gen account
# Copy A... into nats-server.conf -> auth_callout.issuer

# 2. Start NATS server
nats-server -c generated/okta-ropc/go/nats-server.conf

# 3. Start service
export SIGNING_KEY_SEED=SA...
export OKTA_DOMAIN=https://dev-XXXXX.okta.com
export OKTA_CLIENT_ID=...
export OKTA_CLIENT_SECRET=...
./authcallout-okta-ropc service
```

## Testing

```bash
./authcallout-okta-ropc client user@example.com mypassword
nats pub test "hello" --user user@example.com --password mypassword -s nats://localhost:4222
```

## Troubleshooting

| Error | Fix |
|---|---|
| `okta returned 400` | Check `OKTA_CLIENT_ID`, `OKTA_CLIENT_SECRET`, and that ROPC is enabled |
| `okta returned 401` | Wrong username/password |
| `no access_token in okta response` | Okta returned an error — check Okta application settings |
| `not authorized` on service start | Signing key mismatch |
