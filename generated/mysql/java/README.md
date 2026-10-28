# NATS AuthCallout — MySQL Example (Java)

## Overview

Clients connect with username/password. The AuthCallout service validates credentials
against a **MySQL table** storing SHA-256 password hashes. Same NATS server topology as the
KV Store scenario — only the backend handler differs.

```
Client (alice/alice123)  →  NATS Server  →  AuthCallout Service  →  MySQL (users table)
```

## Prerequisites

- `nats-server` >= 2.10, `nats` CLI, `nk` CLI
- MySQL 8.x running on localhost:3306
- Java 17+, Maven 3.8+

## Configuration

### 1. Generate signing NKey
```bash
nk -gen account
# A... → nats-server.conf issuer
# SA... → config.properties auth.signing.key.seed
```

### 2. Fill placeholders in `nats-server.conf` and `config.properties`
Also set `mysql.password` to your MySQL root password.

## Build
```bash
cd generated/mysql/java && mvn package -q
# → target/authcallout-mysql-1.0.0.jar
```

## Startup Sequence

```bash
# 1. MySQL — create database and test users
mysql -u root -p < generated/mysql/java/setup.sql

# 2. NATS server
nats-server -c generated/mysql/java/nats-server.conf

# 3. AuthCallout service
java -jar generated/mysql/java/target/authcallout-mysql-1.0.0.jar \
     service generated/mysql/java/config.properties
```

## Testing

```bash
# Success
nats pub test "hello" --user alice --password alice123 -s nats://localhost:4222

# Wrong password
nats pub test "hello" --user alice --password wrongpassword -s nats://localhost:4222

# Java client
java -jar generated/mysql/java/target/authcallout-mysql-1.0.0.jar \
     client nats://localhost:4222 alice alice123 test
```

## Troubleshooting

| Error | Fix |
|---|---|
| `Authorization Violation` | Signing key mismatch or wrong password |
| `Communications link failure` | MySQL not running or wrong JDBC URL/credentials |
| `Table 'authcallout_db.users' doesn't exist` | Run `setup.sql` first |
