# NATS AuthCallout — MySQL Example (Go)

## Overview

A centralized NATS AuthCallout service that validates username/password credentials
against a **MySQL database**. Passwords are stored as SHA-256 hex strings.

```
Client (user/pass)          NATS Server (port 4222)
     |                            |
     |--- connect (user/pass) --->|
     |                            |--- $SYS.REQ.USER.AUTH ---> AuthCallout Service
     |                            |                             SELECT password_hash
     |                            |                             compare SHA-256(pass)
     |<--- connected in APP ------| <-- signed JWT ------------|
```

## Prerequisites

- `nats-server` >= 2.10, `nats` CLI, `nk` CLI
- MySQL 5.7+ (or MariaDB 10.3+)
- Go 1.21+

## Configuration

| Variable | Required | Default | Description |
|---|---|---|---|
| `SIGNING_KEY_SEED` | **yes** | — | Account NKey seed (`SA...`) |
| `NATS_URL` | no | `nats://localhost:4222` | NATS server URL |
| `SERVICE_USER` | no | `auth` | Auth service username |
| `SERVICE_PASS` | no | `auth` | Auth service password |
| `DEFAULT_ACCOUNT` | no | `APP` | Target account for authenticated users |
| `MYSQL_DSN` | no | `root:password@tcp(localhost:3306)/authcallout_db` | MySQL DSN |
| `MYSQL_QUERY` | no | `SELECT password_hash FROM users WHERE username = ?` | Auth query |

## Build

```bash
cd generated/mysql/go
make build
```

## Startup Sequence

```bash
# 1. Generate signing NKey
nk -gen account
# Copy A... into nats-server.conf -> auth_callout.issuer
# Copy SA... -> SIGNING_KEY_SEED

# 2. Start NATS server
nats-server -c generated/mysql/go/nats-server.conf

# 3. Create database and seed users
bash generated/mysql/go/setup.sh

# 4. Start service
SIGNING_KEY_SEED=SA... MYSQL_DSN="root:password@tcp(localhost:3306)/authcallout_db" \
  ./authcallout-mysql service
```

## Testing

```bash
./authcallout-mysql client alice alice123
nats pub test "hello" --user alice --password alice123 -s nats://localhost:4222
```

## Troubleshooting

| Error | Fix |
|---|---|
| `ping MySQL` | Check MySQL is running and `MYSQL_DSN` is correct |
| `user not found` | Run `setup.sh` or insert user manually |
| `invalid password` | Verify hash: `SELECT SHA2('yourpass', 256)` in MySQL |
| `not authorized` on service start | Signing key mismatch — regenerate with `nk -gen account` |
