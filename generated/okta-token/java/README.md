# NATS AuthCallout — Okta Token Introspection Example (Java)

## Overview

Clients connect using an **Okta access token** (not username/password). The token is passed
as the NATS `auth_token` in CONNECT options. The AuthCallout service calls Okta's token
introspection endpoint to verify the token is active.

```
Client (access_token)  →  NATS Server  →  AuthCallout Service  →  Okta /introspect
```

## Prerequisites

- `nats-server` >= 2.10, `nk` CLI
- Okta or Auth0 tenant with an API application and token introspection enabled
- Java 17+, Maven 3.8+

## Configuration

1. `nk -gen account` → fill `issuer` and `auth.signing.key.seed`
2. Fill `okta.domain`, `okta.client.id`, `okta.client.secret` in `config.properties`

## Build
```bash
cd generated/okta-token/java && mvn package -q
# → target/authcallout-okta-token-1.0.0.jar
```

## Get an Access Token (for testing)

```bash
# Client Credentials flow (machine-to-machine)
TOKEN=$(curl -s -X POST https://your-org.okta.com/oauth2/v1/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=CLIENT_ID&client_secret=CLIENT_SECRET&scope=openid' \
  | jq -r .access_token)
echo $TOKEN
```

## Startup Sequence
```bash
# 1. NATS server
nats-server -c generated/okta-token/java/nats-server.conf

# 2. AuthCallout service
java -jar generated/okta-token/java/target/authcallout-okta-token-1.0.0.jar \
     service generated/okta-token/java/config.properties
```

## Testing
```bash
# With NATS CLI — pass token as --auth-token (equivalent to auth_token in CONNECT)
nats pub test "hello" --creds /dev/null --token $TOKEN -s nats://localhost:4222

# With Java client
java -jar generated/okta-token/java/target/authcallout-okta-token-1.0.0.jar \
     client nats://localhost:4222 $TOKEN test
```

## Troubleshooting

| Error | Fix |
|---|---|
| `Token is inactive or invalid` | Token expired or wrong Okta app client_id/secret |
| `Authorization Violation` | Signing key mismatch |
| `Okta token validation error` | Network issue or wrong `okta.domain` / `okta.introspect.path` |
