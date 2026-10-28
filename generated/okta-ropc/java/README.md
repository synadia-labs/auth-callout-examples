# NATS AuthCallout — Okta ROPC Example (Java)

## Overview

Clients connect with username/password. The AuthCallout service forwards the credentials
to **Okta** (or Auth0) using the **Resource Owner Password Credentials (ROPC)** OAuth2 flow.
A successful Okta token response means authentication passes.

> **Note:** Okta deprecated ROPC in Identity Engine orgs. Use a Classic Okta org, or Auth0
> (set `okta.token.path=/oauth/token` in config.properties).

## Prerequisites

- `nats-server` >= 2.10, `nats` CLI, `nk` CLI
- Okta or Auth0 tenant with ROPC enabled and a configured application
- Java 17+, Maven 3.8+

## Configuration

1. `nk -gen account` → fill `issuer` in `nats-server.conf` and `auth.signing.key.seed` in `config.properties`
2. Fill `okta.domain`, `okta.client.id`, `okta.client.secret` in `config.properties`

## Build
```bash
cd generated/okta-ropc/java && mvn package -q
# → target/authcallout-okta-ropc-1.0.0.jar
```

## Startup Sequence
```bash
# 1. NATS server
nats-server -c generated/okta-ropc/java/nats-server.conf

# 2. AuthCallout service
java -jar generated/okta-ropc/java/target/authcallout-okta-ropc-1.0.0.jar \
     service generated/okta-ropc/java/config.properties
```

## Testing
```bash
# Replace with real Okta user credentials
nats pub test "hello" --user alice@example.com --password MyOktaPass -s nats://localhost:4222

# Java client
java -jar generated/okta-ropc/java/target/authcallout-okta-ropc-1.0.0.jar \
     client nats://localhost:4222 alice@example.com MyOktaPass test
```

## Troubleshooting

| Error | Fix |
|---|---|
| `Authentication failed` | Wrong Okta credentials or ROPC not enabled for the app |
| `Okta authentication error` | Network issue or wrong `okta.domain` |
| `Authorization Violation` | Signing key mismatch |
