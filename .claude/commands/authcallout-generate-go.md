# NATS AuthCallout Asset Generator — Go

Generate complete, working NATS AuthCallout assets for a selected scenario in **Go**.

## Usage

```
/authcallout-generate-go [use-case] [output-dir]
```

`output-dir` is optional. If omitted, files are written under `generated/<use-case>/go/`
relative to the current working directory.

| use-case | Description |
|---|---|
| `kv-store` | Centralized: username/password validated against NATS KV Store (SHA-256) |
| `mysql` | Centralized: username/password validated against MySQL |
| `okta-ropc` | Centralized: username/password via Okta ROPC flow |
| `okta-token` | Centralized: JWT access token via Okta introspection |
| `decentralized` | Decentralized: nsc operator/accounts, NKey challenge-response + KV whitelist |
| `leaf-sentinel` | Hub+Leaf: leaf nodes authenticate via NKey with Ed25519 nonce verification |

---

## Instructions

The user invoked this skill with arguments: $ARGUMENTS

**Step 1 — Parse arguments.**
Extract the first word as `use-case` and the optional second word as `output-dir`.
If `use-case` is missing or unrecognized, show the table above and ask before continuing.

**Step 2 — Resolve output directory.**
- If `output-dir` was provided, write all files under `<output-dir>/` (absolute or relative to CWD).
- If not provided, write files under `generated/<use-case>/go/` relative to CWD.

**Step 3 — Confirm.**
State which scenario and output directory you are about to generate to, then proceed.

**Step 4 — Generate files.**
Write all files under the resolved output directory from Step 2.
Use the Write tool for every file.

**Step 5 — Compile.**
```bash
cd <output-dir> && go mod tidy && go build -o authcallout-<use-case> .
```
On success: "Compiled — binary at <output-dir>/authcallout-<use-case>"
On failure: show the Go error and stop.

**Step 6 — Print Next Steps.**
After compiling, print the exact shell commands to set up and test the scenario.

---

## Common Go Patterns

### Module dependencies

```
module example.com/authcallout-SCENARIO

go 1.21

require (
    github.com/nats-io/nats.go  v1.37.0
    github.com/nats-io/jwt/v2   v2.7.3
    github.com/nats-io/nkeys    v0.4.8
)
```
Add `github.com/go-sql-driver/mysql v1.7.1` for the `mysql` scenario only.

### Makefile (generate in every scenario)

```makefile
.PHONY: build run-service run-client clean

build:
	go mod tidy && go build -o authcallout-SCENARIO .

run-service: build
	./authcallout-SCENARIO service

run-client: build
	./authcallout-SCENARIO client $(ARGS)

clean:
	rm -f authcallout-SCENARIO
```

### AuthCallout service — subscribe and respond

```go
import (
    "log"
    "github.com/nats-io/nats.go"
    natsjwt "github.com/nats-io/jwt/v2"
    "github.com/nats-io/nkeys"
)

// Connect as service user
nc, err := nats.Connect(natsURL, nats.UserInfo("auth", "auth"))

// Load signing key
kp, err := nkeys.FromSeed([]byte(signingKeySeed))
defer kp.Wipe()

// Subscribe to AuthCallout subject
nc.Subscribe("$SYS.REQ.USER.AUTH", func(msg *nats.Msg) {
    // Decode the AuthorizationRequest JWT
    rc, err := natsjwt.DecodeAuthorizationRequestClaims(string(msg.Data))
    if err != nil {
        log.Println("decode error:", err)
        return
    }
    ar := rc.AuthorizationRequest

    // Authenticate (scenario-specific)
    result := authenticate(ar.ConnectOptions.Username, ar.ConnectOptions.Password)

    var responseJwt string
    if result.success {
        // Build inner user JWT
        uc := natsjwt.NewUserClaims(ar.UserNkey)
        uc.Name = ar.ConnectOptions.Username
        uc.Audience = defaultAccount
        userJwt, _ := uc.Encode(kp)

        // Build outer response JWT
        resp := natsjwt.NewAuthorizationResponseClaims(ar.UserNkey)
        resp.Audience = ar.Server.ID
        resp.Jwt = userJwt
        responseJwt, _ = resp.Encode(kp)
    } else {
        resp := natsjwt.NewAuthorizationResponseClaims(ar.UserNkey)
        resp.Audience = ar.Server.ID
        resp.Error = result.reason
        responseJwt, _ = resp.Encode(kp)
    }
    msg.Respond([]byte(responseJwt))
})
nc.Flush()
```

### Decentralized JWTs (issuerAccount required)

```go
// Connect with .creds file instead of username/password
nc, _ := nats.Connect(natsURL, nats.UserCredentials(serviceCredsPath))

// Inner user JWT — add IssuerAccount
uc := natsjwt.NewUserClaims(ar.UserNkey)
uc.Name = identity
uc.Audience = defaultAccount
uc.IssuerAccount = issuerAccount   // AUTH account public key
userJwt, _ := uc.Encode(kp)

// Outer response JWT — add IssuerAccount
resp := natsjwt.NewAuthorizationResponseClaims(ar.UserNkey)
resp.Audience = ar.Server.ID
resp.Jwt = userJwt
resp.IssuerAccount = issuerAccount
responseJwt, _ := resp.Encode(kp)
```

### Client — username/password

```go
nc, err := nats.Connect(url, nats.UserInfo(username, password))
```

### Client — access token (Okta)

```go
nc, err := nats.Connect(url, nats.Token(accessToken))
```

### Client — NKey challenge-response

```go
kp, _ := nkeys.FromSeed([]byte(nkeySeed))
pub, _ := kp.PublicKey()

nc, err := nats.Connect(url, nats.Nkey(pub, func(nonce []byte) ([]byte, error) {
    return kp.Sign(nonce)
}))
```

### Client — credentials file

```go
nc, err := nats.Connect(url, nats.UserCredentials(credsPath))
```

### Nonce signature verification (leaf-sentinel)

```go
import (
    "encoding/base64"
    "github.com/nats-io/nkeys"
)

func verifyNonce(nkeyPublic, nonce, sigB64url string) error {
    kp, err := nkeys.FromPublicKey(nkeyPublic)
    if err != nil { return err }
    sig, err := base64.RawURLEncoding.DecodeString(sigB64url)
    if err != nil { return err }
    return kp.Verify([]byte(nonce), sig)
}
```

### SHA-256 hex helper

```go
import (
    "crypto/sha256"
    "encoding/hex"
)

func sha256Hex(s string) string {
    h := sha256.Sum256([]byte(s))
    return hex.EncodeToString(h[:])
}
```

### NATS KV lookup

```go
js, _ := nc.JetStream()
kv, _ := js.KeyValue(bucketName)

entry, err := kv.Get(key)
if err != nil { /* not found */ }
value := string(entry.Value())
```

### Config loading (use environment variables or a .env file)

```go
import "os"

func getEnv(key, def string) string {
    if v := os.Getenv(key); v != "" { return v }
    return def
}
```

### Graceful shutdown

```go
import (
    "os"
    "os/signal"
    "syscall"
)

quit := make(chan os.Signal, 1)
signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
<-quit
fmt.Println("Shutting down...")
nc.Drain()
```

---

## Files to generate per scenario

For every scenario generate these files:

| File | Description |
|---|---|
| `go.mod` | Go module with correct dependencies |
| `main.go` | Entry point: routes `service` or `client` subcommand |
| `service.go` | AuthCallout service: subscribe, decode, authenticate, respond |
| `<backend>_handler.go` | Backend-specific auth logic (kv, mysql, okta_ropc, okta_token, nkey_kv) |
| `client.go` | Demo client with appropriate auth method |
| `config.go` | Config struct loaded from environment variables |
| `Makefile` | `make build`, `make run-service`, `make run-client` |
| `nats-server.conf` | NATS server configuration (or hub+leaf configs for leaf-sentinel) |
| `setup.sh` | Environment setup (KV buckets, test users, nsc commands if decentralized) |
| `README.md` | Overview, prerequisites, build, startup sequence, testing, troubleshooting |

---

## Scenario Specifications

---

### Scenario: `kv-store`

**nats-server.conf:** same as Java kv-store — accounts SYS/AUTH/APP, auth_callout block with issuer placeholder.

**config.go pattern:**
```go
type Config struct {
    NatsURL        string
    ServiceUser    string
    ServicePass    string
    SigningKeySeed string
    DefaultAccount string
    KvBucketName   string
}

func LoadConfig() Config {
    return Config{
        NatsURL:        getEnv("NATS_URL",         "nats://localhost:4222"),
        ServiceUser:    getEnv("SERVICE_USER",      "auth"),
        ServicePass:    getEnv("SERVICE_PASS",      "auth"),
        SigningKeySeed: mustEnv("SIGNING_KEY_SEED"),
        DefaultAccount: getEnv("DEFAULT_ACCOUNT",  "APP"),
        KvBucketName:   getEnv("KV_BUCKET",        "auth-users"),
    }
}

func mustEnv(key string) string {
    v := os.Getenv(key)
    if v == "" { log.Fatalf("required env var %s not set", key) }
    return v
}
```

**kv_handler.go pattern:**
```go
type KvHandler struct {
    kv             nats.KeyValue
    defaultAccount string
}

func NewKvHandler(js nats.JetStreamContext, bucket, account string) (*KvHandler, error) {
    kv, err := js.KeyValue(bucket)
    if err != nil { return nil, err }
    return &KvHandler{kv: kv, defaultAccount: account}, nil
}

func (h *KvHandler) Authenticate(username, password string) (bool, string, error) {
    entry, err := h.kv.Get(username)
    if err != nil { return false, "", fmt.Errorf("user not found: %s", username) }
    if string(entry.Value()) == sha256Hex(password) { return true, h.defaultAccount, nil }
    return false, "", fmt.Errorf("invalid password")
}
```

**service.go:** subscribes to `$SYS.REQ.USER.AUTH`, decodes JWT, calls `KvHandler.Authenticate`, builds and sends response JWT.

**client.go:** `nats.Connect(url, nats.UserInfo(username, password))`

**setup.sh:**
```bash
#!/usr/bin/env bash
set -e
nats kv add auth-users --user auth --password auth -s nats://localhost:4222
nats kv put auth-users alice $(echo -n "alice123" | sha256sum | awk '{print $1}') \
    --user auth --password auth -s nats://localhost:4222
nats kv put auth-users bob $(echo -n "bobpass" | sha256sum | awk '{print $1}') \
    --user auth --password auth -s nats://localhost:4222
```

**Next Steps:**
```bash
# 1. Generate signing NKey
nk -gen account
# A... → NATS_SIGNING_KEY_PUBLIC in nats-server.conf
# SA... → SIGNING_KEY_SEED env var

# 2. Start NATS server
nats-server -c nats-server.conf

# 3. Create KV bucket + users
bash setup.sh

# 4. Build
make build

# 5. Start service
SIGNING_KEY_SEED=SA... ./authcallout-kv-store service

# 6. Test
nats pub test "hello" --user alice --password alice123 -s nats://localhost:4222
./authcallout-kv-store client alice alice123
```

---

### Scenario: `mysql`

**mysql_handler.go pattern:**
```go
import (
    "database/sql"
    _ "github.com/go-sql-driver/mysql"
)

type MySQLHandler struct {
    db             *sql.DB
    query          string
    defaultAccount string
}

func NewMySQLHandler(dsn, query, account string) (*MySQLHandler, error) {
    db, err := sql.Open("mysql", dsn)
    if err != nil { return nil, err }
    return &MySQLHandler{db: db, query: query, defaultAccount: account}, nil
}

func (h *MySQLHandler) Authenticate(username, password string) (bool, string, error) {
    var storedHash string
    err := h.db.QueryRow(h.query, username).Scan(&storedHash)
    if err == sql.ErrNoRows { return false, "", fmt.Errorf("user not found") }
    if err != nil { return false, "", err }
    if storedHash == sha256Hex(password) { return true, h.defaultAccount, nil }
    return false, "", fmt.Errorf("invalid password")
}

func (h *MySQLHandler) Close() { h.db.Close() }
```

**Config env vars:**
```
NATS_URL, SERVICE_USER, SERVICE_PASS, SIGNING_KEY_SEED, DEFAULT_ACCOUNT
MYSQL_DSN=root:password@tcp(localhost:3306)/authcallout_db
MYSQL_QUERY=SELECT password_hash FROM users WHERE username = ?
```

**setup.sql:** same as Java — `CREATE TABLE users`, `INSERT INTO users` with `SHA2()`.

---

### Scenario: `okta-ropc`

**okta_ropc_handler.go pattern:**
```go
import (
    "encoding/json"
    "io"
    "net/http"
    "net/url"
    "time"
)

type OktaRopcHandler struct {
    domain, clientID, clientSecret, scopes, tokenPath, defaultAccount string
    client *http.Client
}

func NewOktaRopcHandler(domain, clientID, clientSecret, scopes, tokenPath, account string) *OktaRopcHandler {
    return &OktaRopcHandler{
        domain: domain, clientID: clientID, clientSecret: clientSecret,
        scopes: scopes, tokenPath: tokenPath, defaultAccount: account,
        client: &http.Client{Timeout: 10 * time.Second},
    }
}

func (h *OktaRopcHandler) Authenticate(username, password string) (bool, string, error) {
    form := url.Values{
        "grant_type":    {"password"},
        "username":      {username},
        "password":      {password},
        "scope":         {h.scopes},
        "client_id":     {h.clientID},
        "client_secret": {h.clientSecret},
    }
    resp, err := h.client.PostForm(h.domain+h.tokenPath, form)
    if err != nil { return false, "", err }
    defer resp.Body.Close()
    body, _ := io.ReadAll(resp.Body)
    if resp.StatusCode != 200 { return false, "", fmt.Errorf("okta status %d: %s", resp.StatusCode, body) }
    var result map[string]interface{}
    json.Unmarshal(body, &result)
    if _, ok := result["access_token"]; ok { return true, h.defaultAccount, nil }
    return false, "", fmt.Errorf("no access_token in response")
}
```

**Config env vars:**
```
OKTA_DOMAIN, OKTA_CLIENT_ID, OKTA_CLIENT_SECRET
OKTA_SCOPES=openid profile email
OKTA_TOKEN_PATH=/oauth2/v1/token
```

---

### Scenario: `okta-token`

**okta_introspect_handler.go pattern:**
```go
func (h *OktaIntrospectHandler) AuthenticateToken(token string) (bool, string, error) {
    form := url.Values{"token": {token}, "token_type_hint": {"access_token"}}
    req, _ := http.NewRequest("POST", h.domain+h.introspectPath,
        strings.NewReader(form.Encode()))
    req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    req.SetBasicAuth(h.clientID, h.clientSecret)

    resp, err := h.client.Do(req)
    if err != nil { return false, "", err }
    defer resp.Body.Close()
    body, _ := io.ReadAll(resp.Body)
    var result map[string]interface{}
    json.Unmarshal(body, &result)
    active, _ := result["active"].(bool)
    if active { return true, h.defaultAccount, nil }
    return false, "", fmt.Errorf("token inactive or invalid")
}
```

**service.go change:** check `ar.ConnectOptions.Token` first:
```go
if ar.ConnectOptions.Token != "" {
    ok, account, err := handler.AuthenticateToken(ar.ConnectOptions.Token)
    // ... build response
} else {
    // reject or handle user/pass
}
```

**client.go:**
```go
nc, _ := nats.Connect(url, nats.Token(os.Args[1]))
```

**Config env vars:**
```
OKTA_DOMAIN, OKTA_CLIENT_ID, OKTA_CLIENT_SECRET
OKTA_INTROSPECT_PATH=/oauth2/v1/introspect
```

---

### Scenario: `decentralized`

**service.go differences from kv-store:**
- Connect with `nats.UserCredentials(serviceCredsPath)` instead of UserInfo
- Load signing key seed from `.nk` file: `os.ReadFile(signingKeyPath)`
- Route on `ar.ConnectOptions.Username != ""` → password; else → NKey via `ar.ConnectOptions.Nkey`
- Add `IssuerAccount` to both user JWT and response JWT

```go
// Read signing key from .nk file
seedBytes, _ := os.ReadFile(signingKeyPath)
kp, _ := nkeys.FromSeed(bytes.TrimSpace(seedBytes))

// Connect with .creds
nc, _ := nats.Connect(natsURL, nats.UserCredentials(serviceCredsPath))

// In handler — decentralized JWT building
uc := natsjwt.NewUserClaims(ar.UserNkey)
uc.Name = identity
uc.Audience = defaultAccount
uc.IssuerAccount = issuerAccount
userJwt, _ := uc.Encode(kp)

resp := natsjwt.NewAuthorizationResponseClaims(ar.UserNkey)
resp.Audience = ar.Server.ID
resp.Jwt = userJwt
resp.IssuerAccount = issuerAccount
responseJwt, _ := resp.Encode(kp)
```

**nkey_kv_handler.go:**
```go
func (h *NKeyKvHandler) Authenticate(nkeyPublic string) (bool, string, error) {
    entry, err := h.kv.Get(nkeyPublic)
    if err != nil || entry == nil || len(entry.Value()) == 0 {
        return false, "", fmt.Errorf("NKey not authorized")
    }
    return true, h.defaultAccount, nil
}
```

**NKey client:**
```go
kp, _ := nkeys.FromSeed([]byte(nkeySeed))
pub, _ := kp.PublicKey()
nc, _ := nats.Connect(url, nats.Nkey(pub, func(nonce []byte) ([]byte, error) {
    return kp.Sign(nonce)
}))
```

**setup.sh:** same nsc commands as Java decentralized scenario.

**Config env vars:**
```
NATS_URL, SERVICE_CREDS, SIGNING_KEY_PATH
ISSUER_ACCOUNT, DEFAULT_ACCOUNT
KV_BUCKET=auth-users, NKEY_BUCKET=auth-nkeys
NKEY_ENABLED=true
```

---

### Scenario: `leaf-sentinel`

**nats-server-hub.conf:** same as Java — includes `resolver.conf`, `default_sentinel`, `leafnodes { listen: 7422 }`.

**nats-server-leaf.conf:** same as Java — `port: 4223`, `leafnodes { remotes [{ url: nats-leaf://localhost:7422, nkey: SU... }] }`.

**service.go critical addition — nonce verification before KV lookup:**
```go
func verifyNonce(ar natsjwt.AuthorizationRequest, clientNKey string) error {
    if ar.ClientInformation.Nonce == "" {
        return fmt.Errorf("nonce missing")
    }
    if ar.ConnectOptions.SignedNonce == "" {
        return fmt.Errorf("signature missing")
    }
    kp, err := nkeys.FromPublicKey(clientNKey)
    if err != nil { return fmt.Errorf("invalid nkey: %w", err) }
    sig, err := base64.RawURLEncoding.DecodeString(ar.ConnectOptions.SignedNonce)
    if err != nil { return fmt.Errorf("invalid sig encoding: %w", err) }
    return kp.Verify([]byte(ar.ClientInformation.Nonce), sig)
}
```

**NKey path in handler:**
```go
clientNKey := ar.ConnectOptions.Nkey
if clientNKey == "" {
    // reject
}
if err := verifyNonce(ar, clientNKey); err != nil {
    log.Printf("nonce verification failed — kind: %s, nkey: %s: %v",
        ar.ClientInformation.Kind, clientNKey, err)
    // send error response
}
// Then KV whitelist lookup
```

Log `ar.ClientInformation.Kind` to distinguish `"Leafnode"` from `"Client"` in logs.

**Startup order (critical):**
1. Hub NATS server
2. LeafSentinel AuthCallout service (must be listening before leaf connects)
3. Leaf NATS server

**setup.sh:** same nsc commands as decentralized, plus leaf NKey generation steps:
```bash
echo "Generate leaf NKey:"
echo "  nk -gen server"
echo "  # seed (SU...) → nats-server-leaf.conf nkey field"
echo "  # public key (U...) → nats kv put auth-nkeys <KEY> 'leaf-node' ..."
```

---

## README Template

Generate a `README.md` for the chosen scenario including:

1. **Overview** — one paragraph + ASCII diagram of the auth flow
2. **Prerequisites** — `nats-server`, `nats` CLI, `nk` CLI, `nsc` (if decentralized), Go 1.21+
3. **Configuration** — env vars to set before running (list every env var with example values)
4. **Build**
   ```bash
   cd generated/<scenario>/go
   make build
   # OR: go mod tidy && go build -o authcallout-<scenario> .
   ```
5. **Startup sequence** — numbered steps with exact commands
6. **Running the service**
   ```bash
   SIGNING_KEY_SEED=SA... ./authcallout-<scenario> service
   ```
7. **Running the client**
   ```bash
   ./authcallout-<scenario> client alice alice123
   # or for NKey:
   ./authcallout-<scenario> client SUNKEY...
   ```
8. **Testing with NATS CLI** — success and failure examples
9. **Troubleshooting** — same table as Java version:
   - `not authorized` → signing key mismatch
   - `user not found` / `NKey not authorized` → missing KV entry
   - `nonce verification failed` → NKey seed/public key mismatch
   - `not valid` → stale default_sentinel JWT
   - leaf port conflict → set explicit `port: 4223`
