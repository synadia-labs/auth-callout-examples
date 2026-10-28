# NATS AuthCallout Asset Generator

Generate complete, working NATS AuthCallout assets for a selected scenario and programming language.

## Usage

```
/authcallout-generate [use-case] [language]
```

| Argument | Values |
|---|---|
| `use-case` | `kv-store` \| `mysql` \| `okta-ropc` \| `okta-token` \| `decentralized` \| `leaf-sentinel` |
| `language` | `java` \| `go` |

---

## Instructions

The user invoked this skill with arguments: $ARGUMENTS

**Step 1 — Parse arguments.**
Extract the first word as `use-case` and the second word as `language`.
If either is missing or unrecognized, show the table above and ask the user to choose before continuing.

**Step 2 — Confirm before writing.**
State which scenario and language you are about to generate, then proceed.

**Step 3 — Generate files.**
Write all files for the selected scenario under:
```
generated/<use-case>/<language>/
```
relative to the current working directory (the project root).

Use the Write tool for each file. Do not skip any file.
Always generate the build file (`pom.xml` for Java, `go.mod` for Go) as part of the file set.

**Step 4 — Compile.**
After writing all source files, compile them using the Bash tool:

- Java: `cd generated/<use-case>/java && mvn package -q`
  On success, print: "Compiled — jar at generated/<use-case>/java/target/authcallout-<use-case>-1.0.0.jar"
  On failure, show the Maven error and stop.

- Go: `cd generated/<use-case>/go && go mod tidy && go build -o authcallout-<use-case> ./...`
  On success, print: "Compiled — binary at generated/<use-case>/go/authcallout-<use-case>"
  On failure, show the Go error and stop.

**Step 5 — Print Next Steps.**
After compiling, print a "Next Steps" section with the exact shell commands
the user needs to run to set up and test the scenario, referencing the compiled artifact.

---

## Scenario Definitions

---

### Scenario: `kv-store`

**What it does:** Centralized NATS server with accounts. Clients connect with username/password.
The AuthCallout service looks up the username in a NATS KV bucket and compares SHA-256(password) against the stored hash.

**Architecture:**
- NATS server runs accounts: SYS (system), AUTH (service lives here, JetStream enabled), APP (clients land here)
- `authorization.auth_callout` block points the server at the AUTH account for callout
- AuthCallout service connects as `auth` user in AUTH account, listens on `$SYS.REQ.USER.AUTH`
- KV bucket `auth-users` in AUTH account JetStream: key=username, value=SHA-256 hex of password
- Client connects with username+password to the NATS server port

**Files to generate (7 files):**

1. `nats-server.conf`
2. `config.properties`
3. `AuthCalloutService.java` (Java) or `authcallout_service.go` (Go)
4. `KvStoreAuthHandler.java` (Java) or `kv_auth_handler.go` (Go)
5. `Client.java` (Java) or `client.go` (Go)
6. `setup.sh` — shell script to create KV bucket and add test users
7. `README.md`

**nats-server.conf:**
```conf
# NATS AuthCallout — KV Store password backend
server_name: authcallout-kv-server
port: 4222
http_port: 8222

accounts {
  SYS: {
    users: [ { user: sys, password: sys } ]
  }
  AUTH: {
    jetstream: true
    users: [
      { user: auth, password: auth }
    ]
  }
  APP: {
    jetstream: true
  }
}

system_account: SYS

authorization {
  auth_callout {
    # Replace with: nsc generate nkey --account  (use the A... public key here)
    issuer: "REPLACE_WITH_AUTH_ACCOUNT_SIGNING_KEY_PUBLIC"
    account: AUTH
    auth_users: [ auth ]
  }
}
```

**config.properties:**
```properties
nats.url=nats://localhost:4222
nats.service.user=auth
nats.service.password=auth
# Generate with: nsc generate nkey --account
# Use the SA... seed here (the A... public key goes in nats-server.conf issuer field)
auth.signing.key.seed=REPLACE_WITH_SA_SEED
auth.default.account=APP
auth.password.backend=kv
kv.bucket.name=auth-users
```

**Java AuthCalloutService.java pattern:**
```java
package io.example.authcallout;

import io.nats.client.*;
import io.nats.jwt.*;
import io.nats.nkey.NKey;
import io.nats.service.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AuthCalloutService implements ServiceMessageHandler {

    private static final String SUBJECT = "$SYS.REQ.USER.AUTH";

    private final Connection nc;
    private final KvStoreAuthHandler handler;
    private final NKey signingKey;
    private final String signingKeyPublic;
    private Service service;
    private CompletableFuture<Boolean> doneFuture;

    public AuthCalloutService(Connection nc, KvStoreAuthHandler handler, String signingKeySeed) throws Exception {
        this.nc = nc;
        this.handler = handler;
        this.signingKey = NKey.fromSeed(signingKeySeed.toCharArray());
        this.signingKeyPublic = new String(signingKey.getPublicKey());
    }

    public void start() throws Exception {
        Endpoint ep = Endpoint.builder().name("AuthCalloutEndpoint").subject(SUBJECT).build();
        ServiceEndpoint sep = ServiceEndpoint.builder().endpoint(ep).handler(this).build();
        service = new ServiceBuilder()
                .connection(nc).name("AuthCalloutService").version("1.0.0")
                .description("KV Store AuthCallout").addServiceEndpoint(sep).build();
        doneFuture = service.startService();
        System.out.println("AuthCalloutService started on " + SUBJECT);
    }

    public void stop() { if (service != null) service.stop(); }
    public CompletableFuture<Boolean> doneFuture() { return doneFuture; }

    @Override
    public void onMessage(ServiceMessage smsg) {
        try {
            Claim claim = new Claim(io.nats.jwt.JwtUtils.getClaimBody(smsg.getData()));
            AuthorizationRequest ar = claim.authorizationRequest;
            if (ar == null || ar.connectOpts == null) return;

            AuthResult result = handler.authenticate(ar.connectOpts.user, ar.connectOpts.pass);

            String responseJwt;
            if (result.isSuccess()) {
                UserClaim uc = new UserClaim();
                String userJwt = new ClaimIssuer()
                        .aud(result.account).name(ar.connectOpts.user)
                        .iss(signingKeyPublic).sub(ar.userNkey)
                        .nats(uc).issueJwt(signingKey);
                AuthorizationResponse resp = new AuthorizationResponse().jwt(userJwt);
                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id).iss(signingKeyPublic)
                        .sub(ar.userNkey).nats(resp).issueJwt(signingKey);
            } else {
                AuthorizationResponse resp = new AuthorizationResponse().error(result.error);
                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id).iss(signingKeyPublic)
                        .sub(ar.userNkey).nats(resp).issueJwt(signingKey);
            }
            smsg.respond(nc, responseJwt);
        } catch (Exception e) {
            System.err.println("Auth callout error: " + e.getMessage());
        }
    }
}
```

**Java KvStoreAuthHandler.java pattern:**
```java
package io.example.authcallout;

import io.nats.client.*;
import io.nats.client.api.KeyValueEntry;
import io.nats.jwt.UserClaim;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class KvStoreAuthHandler {
    private final KeyValue kv;
    private final String defaultAccount;

    public KvStoreAuthHandler(Connection nc, String bucketName, String defaultAccount) throws Exception {
        this.kv = nc.keyValue(bucketName);
        this.defaultAccount = defaultAccount;
    }

    public AuthResult authenticate(String username, String password) {
        if (username == null || password == null) return AuthResult.failure("Missing credentials");
        try {
            KeyValueEntry entry = kv.get(username);
            if (entry == null) return AuthResult.failure("User not found");
            String stored = new String(entry.getValue(), StandardCharsets.UTF_8).trim();
            if (stored.equalsIgnoreCase(sha256Hex(password)))
                return AuthResult.success(defaultAccount);
            return AuthResult.failure("Invalid password");
        } catch (Exception e) {
            return AuthResult.failure("Auth error: " + e.getMessage());
        }
    }

    static String sha256Hex(String input) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        byte[] h = d.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
```

**Java Client.java pattern:**
```java
package io.example.authcallout;

import io.nats.client.*;
import java.time.Duration;

public class Client {
    public static void main(String[] args) throws Exception {
        String url      = args.length > 0 ? args[0] : "nats://localhost:4222";
        String username = args.length > 1 ? args[1] : "alice";
        String password = args.length > 2 ? args[2] : "alice123";
        String subject  = args.length > 3 ? args[3] : "test";

        Options opts = new Options.Builder()
                .server(url)
                .userInfo(username, password)
                .maxReconnects(3)
                .reconnectWait(Duration.ofSeconds(1))
                .errorListener(new ErrorListener() {})
                .build();

        try (Connection nc = Nats.connect(opts)) {
            System.out.println("Connected as " + username);
            nc.publish(subject, ("hello from " + username).getBytes());
            System.out.println("Published to " + subject);
            Thread.sleep(500);
        }
    }
}
```

**Go authcallout_service.go pattern:**
```go
package main

import (
    "crypto/sha256"
    "encoding/hex"
    "fmt"
    "log"
    "os"
    "os/signal"
    "syscall"

    "github.com/nats-io/nats.go"
    natsjwt "github.com/nats-io/jwt/v2"
    "github.com/nats-io/nkeys"
)

func main() {
    natsURL       := getEnv("NATS_URL", "nats://localhost:4222")
    signingKeySeed := getEnv("SIGNING_KEY_SEED", "")
    bucketName    := getEnv("KV_BUCKET", "auth-users")
    defaultAccount := getEnv("DEFAULT_ACCOUNT", "APP")

    kp, err := nkeys.FromSeed([]byte(signingKeySeed))
    if err != nil { log.Fatal("Invalid signing key seed:", err) }
    defer kp.Wipe()

    nc, err := nats.Connect(natsURL, nats.UserInfo("auth", "auth"))
    if err != nil { log.Fatal("Connect failed:", err) }
    defer nc.Close()

    js, _ := nc.JetStream()
    kv, err := js.KeyValue(bucketName)
    if err != nil { log.Fatal("KV bucket not found:", err) }

    nc.Subscribe("$SYS.REQ.USER.AUTH", func(msg *nats.Msg) {
        claims := &natsjwt.AuthorizationRequestClaims{}
        if err := natsjwt.Decode(string(msg.Data), claims, nil); err != nil {
            log.Println("Decode error:", err)
            return
        }
        ar := claims.AuthorizationRequest
        username := ar.ConnectOptions.Username
        password := ar.ConnectOptions.Password

        var responseJwt string
        if authenticated, err := checkKv(kv, username, password); authenticated && err == nil {
            uc := natsjwt.NewUserClaims(ar.UserNkey)
            uc.Name = username
            uc.Audience = defaultAccount
            userJwt, _ := uc.Encode(kp)

            rc := natsjwt.NewAuthorizationResponseClaims(ar.UserNkey)
            rc.Audience = ar.ServerID.ID
            rc.Jwt = userJwt
            responseJwt, _ = rc.Encode(kp)
        } else {
            reason := "Authentication failed"
            if err != nil { reason = err.Error() }
            rc := natsjwt.NewAuthorizationResponseClaims(ar.UserNkey)
            rc.Audience = ar.ServerID.ID
            rc.Error = reason
            responseJwt, _ = rc.Encode(kp)
        }
        msg.Respond([]byte(responseJwt))
    })
    nc.Flush()

    fmt.Println("AuthCallout service running (kv-store). Ctrl+C to stop.")
    quit := make(chan os.Signal, 1)
    signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
    <-quit
}

func checkKv(kv nats.KeyValue, username, password string) (bool, error) {
    entry, err := kv.Get(username)
    if err != nil { return false, fmt.Errorf("user not found") }
    stored := string(entry.Value())
    h := sha256.Sum256([]byte(password))
    return stored == hex.EncodeToString(h[:]), nil
}

func getEnv(key, def string) string {
    if v := os.Getenv(key); v != "" { return v }
    return def
}
```

**Go client.go pattern:**
```go
package main

import (
    "fmt"
    "log"
    "github.com/nats-io/nats.go"
)

func main() {
    url      := "nats://localhost:4222"
    username := "alice"
    password := "alice123"
    subject  := "test"

    nc, err := nats.Connect(url, nats.UserInfo(username, password))
    if err != nil { log.Fatal("Connect failed:", err) }
    defer nc.Close()

    fmt.Println("Connected as", username)
    nc.Publish(subject, []byte("hello from "+username))
    nc.Flush()
    fmt.Println("Published to", subject)
}
```

**setup.sh:**
```bash
#!/usr/bin/env bash
set -e
NATS_URL="nats://localhost:4222"
AUTH_USER="--user auth --password auth"

echo "Creating auth-users KV bucket..."
nats kv add auth-users -s $NATS_URL $AUTH_USER

echo "Adding test user alice (password: alice123)..."
nats kv put auth-users alice \
  $(echo -n "alice123" | sha256sum | awk '{print $1}') \
  -s $NATS_URL $AUTH_USER

echo "Adding test user bob (password: bobpass)..."
nats kv put auth-users bob \
  $(echo -n "bobpass" | sha256sum | awk '{print $1}') \
  -s $NATS_URL $AUTH_USER

echo "Done. Users: alice/alice123, bob/bobpass"
```

**Next Steps for kv-store:**
```bash
# 1. Generate signing NKey pair
nk -gen account
# Output:
#   Public key: A...  (paste into nats-server.conf as issuer)
#   Seed:       SA... (paste into config.properties as auth.signing.key.seed)

# 2. Update the two REPLACE_WITH_... placeholders in the generated files

# 3. Start NATS server
nats-server -c generated/kv-store/nats-server.conf

# 4. Run setup script (creates KV bucket + test users)
bash generated/kv-store/setup.sh

# 5. Start AuthCallout service  (Java)
java -cp "target/*" io.example.authcallout.AuthCalloutService

# 5. Start AuthCallout service  (Go)
SIGNING_KEY_SEED=SA... go run generated/kv-store/go/authcallout_service.go

# 6. Test with NATS CLI
nats pub test "hello" --user alice --password alice123 -s nats://localhost:4222
nats pub test "hello" --user baduser --password wrong -s nats://localhost:4222

# 7. Test with Java/Go client
java -cp "target/*" io.example.authcallout.Client nats://localhost:4222 alice alice123 test
go run generated/kv-store/go/client.go
```

---

### Scenario: `mysql`

**What it does:** Same as `kv-store` but password hashes are stored in a MySQL table instead of a KV bucket.
The NATS server config is identical to `kv-store`. Only the handler and config differ.

**Files to generate (7 files):** same structure as kv-store, replacing KvStoreAuthHandler with MySqlAuthHandler.

**Additional config.properties fields:**
```properties
auth.password.backend=mysql
mysql.url=jdbc:mysql://localhost:3306/mydb
mysql.user=root
mysql.password=dbpassword
mysql.query=SELECT password_hash FROM users WHERE username = ?
```

**MySQL setup SQL:**
```sql
CREATE DATABASE IF NOT EXISTS mydb;
USE mydb;
CREATE TABLE IF NOT EXISTS users (
    username      VARCHAR(255) PRIMARY KEY,
    password_hash VARCHAR(64)  NOT NULL
);
-- Add test user alice (password: alice123)
INSERT INTO users VALUES ('alice', SHA2('alice123', 256));
INSERT INTO users VALUES ('bob',   SHA2('bobpass',  256));
```

**Java MySqlAuthHandler.java pattern:**
```java
public class MySqlAuthHandler {
    private final String jdbcUrl, dbUser, dbPassword, query, defaultAccount;

    public MySqlAuthHandler(String jdbcUrl, String dbUser, String dbPassword,
                            String query, String defaultAccount) {
        this.jdbcUrl = jdbcUrl; this.dbUser = dbUser; this.dbPassword = dbPassword;
        this.query = query; this.defaultAccount = defaultAccount;
    }

    public AuthResult authenticate(String username, String password) {
        if (username == null || password == null) return AuthResult.failure("Missing credentials");
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             java.sql.PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, username);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return AuthResult.failure("User not found");
                String stored = rs.getString(1);
                if (stored.equalsIgnoreCase(sha256Hex(password)))
                    return AuthResult.success(defaultAccount);
                return AuthResult.failure("Invalid password");
            }
        } catch (Exception e) { return AuthResult.failure("DB error: " + e.getMessage()); }
    }
    // sha256Hex same as KvStoreAuthHandler
}
```

**Go mysql_auth_handler.go pattern:**
```go
import (
    "crypto/sha256"
    "database/sql"
    "encoding/hex"
    "fmt"
    _ "github.com/go-sql-driver/mysql"
)

func checkMySQL(db *sql.DB, username, password string) (bool, error) {
    var storedHash string
    err := db.QueryRow("SELECT password_hash FROM users WHERE username = ?", username).Scan(&storedHash)
    if err == sql.ErrNoRows { return false, fmt.Errorf("user not found") }
    if err != nil { return false, err }
    h := sha256.Sum256([]byte(password))
    return storedHash == hex.EncodeToString(h[:]), nil
}
```

**Additional Go module dependency:** `github.com/go-sql-driver/mysql v1.7.1`

---

### Scenario: `okta-ropc`

**What it does:** Same NATS server structure as `kv-store`. Instead of a local password store, the AuthCallout
service forwards the username/password to Okta using the Resource Owner Password Credentials (ROPC) OAuth2 flow.
A successful Okta token response means authentication passes.

> NOTE: Okta deprecated ROPC in Identity Engine orgs. If unavailable, use Auth0 or a legacy Okta org,
> or swap the token endpoint for the Okta Authentication API (`/api/v1/authn`).

**Additional config.properties fields:**
```properties
auth.password.backend=okta
okta.domain=https://your-org.okta.com
okta.client.id=YOUR_CLIENT_ID
okta.client.secret=YOUR_CLIENT_SECRET
okta.scopes=openid profile email
okta.token.path=/oauth2/v1/token
okta.introspect.path=/oauth2/v1/introspect
# For Auth0 use: okta.token.path=/oauth/token  okta.introspect.path=/oauth/introspect
```

**Java OktaRopcAuthHandler.java pattern:**
```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class OktaRopcAuthHandler {
    private final String domain, clientId, clientSecret, scopes, tokenPath, defaultAccount;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthResult authenticate(String username, String password) {
        try {
            String body = "grant_type=password"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&scope="    + URLEncoder.encode(scopes, StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(domain + tokenPath))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10)).build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode json = mapper.readTree(resp.body());
                if (json.has("access_token")) return AuthResult.success(defaultAccount);
            }
            return AuthResult.failure("Okta ROPC failed: " + resp.statusCode());
        } catch (Exception e) { return AuthResult.failure("Okta error: " + e.getMessage()); }
    }
}
```

**Go okta_ropc_handler.go pattern:**
```go
import (
    "encoding/json"
    "fmt"
    "io"
    "net/http"
    "net/url"
    "strings"
    "time"
)

func checkOktaROPC(domain, clientID, clientSecret, scopes, tokenPath, username, password string) (bool, error) {
    form := url.Values{}
    form.Set("grant_type", "password")
    form.Set("username", username)
    form.Set("password", password)
    form.Set("scope", scopes)
    form.Set("client_id", clientID)
    form.Set("client_secret", clientSecret)

    client := &http.Client{Timeout: 10 * time.Second}
    resp, err := client.PostForm(domain+tokenPath, form)
    if err != nil { return false, err }
    defer resp.Body.Close()
    body, _ := io.ReadAll(resp.Body)

    if resp.StatusCode != 200 { return false, fmt.Errorf("okta status %d", resp.StatusCode) }
    var result map[string]interface{}
    json.Unmarshal(body, &result)
    _, ok := result["access_token"]
    return ok, nil
}
```

---

### Scenario: `okta-token`

**What it does:** Same NATS server as `kv-store`. The client connects with an opaque Okta access token
(set via `authToken` in CONNECT options, not username/password). The AuthCallout service calls Okta's
token introspection endpoint to validate it.

**Client change:** client sends token as `auth_token`, not username/password.

**Java NatsTokenClient.java pattern:**
```java
Options opts = new Options.Builder()
    .server(url)
    .token(accessToken.toCharArray())
    .errorListener(new ErrorListener() {})
    .build();
Connection nc = Nats.connect(opts);
```

**Java OktaIntrospectHandler.java pattern:**
```java
public AuthResult authenticateToken(String token) {
    try {
        String credentials = clientId + ":" + clientSecret;
        String basicAuth = Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8));
        String body = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
            + "&token_type_hint=access_token";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(domain + introspectPath))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic " + basicAuth)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10)).build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        boolean active = json.path("active").asBoolean(false);
        if (active) return AuthResult.success(defaultAccount);
        return AuthResult.failure("Token inactive or invalid");
    } catch (Exception e) { return AuthResult.failure("Okta introspect error: " + e.getMessage()); }
}
```

**Handler dispatch in service (token path):**
In `onMessage`, check `ar.connectOpts.authToken` first; if non-empty, use the introspect handler.
If `ar.connectOpts.user` is non-empty, use ROPC handler. The service can support both simultaneously.

**Go client_token.go pattern:**
```go
nc, err := nats.Connect(url, nats.Token(accessToken))
```

**Go okta_introspect_handler.go pattern:**
```go
func checkOktaToken(domain, clientID, clientSecret, introspectPath, token string) (bool, error) {
    form := url.Values{}
    form.Set("token", token)
    form.Set("token_type_hint", "access_token")

    req, _ := http.NewRequest("POST", domain+introspectPath, strings.NewReader(form.Encode()))
    req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    req.SetBasicAuth(clientID, clientSecret)

    client := &http.Client{Timeout: 10 * time.Second}
    resp, err := client.Do(req)
    if err != nil { return false, err }
    defer resp.Body.Close()
    body, _ := io.ReadAll(resp.Body)

    var result map[string]interface{}
    json.Unmarshal(body, &result)
    active, _ := result["active"].(bool)
    return active, nil
}
```

---

### Scenario: `decentralized`

**What it does:** Fully decentralized using nsc operator/account model with a NATS resolver.
Three sub-modes in one service:
- Scenario A (explicit sentinel): Client loads a `sentinel.creds` file + sends username/password
- Scenario B (default_sentinel): Server auto-applies bearer JWT; client sends only username/password
- Scenario C (NKey challenge-response): Client holds NKey seed, performs challenge-response; server applies default_sentinel, callout verifies NKey is in `auth-nkeys` KV whitelist

The service is `DecentralizedAuthCalloutService` — it routes by presence of `connectOpts.user`.

**nsc setup commands:**
```bash
# 1. Create operator and accounts
nsc add operator AuthCalloutOperator
nsc add account AUTH
nsc add account APP1
nsc add account SYS

# 2. Create users
nsc add user -a AUTH auth            # service user
nsc add user -a AUTH sentinel --bearer   # default_sentinel bearer (Scenario B/C)
nsc add user -a AUTH sentinel-explicit   # explicit sentinel (Scenario A, no --bearer needed)
nsc add user -a SYS  sys

# 3. Generate credentials
nsc generate creds -a AUTH -n auth     > NATS/auth.creds
nsc generate creds -a AUTH -n sentinel > NATS/default_sentinel.creds
nsc generate creds -a AUTH -n sentinel-explicit > NATS/sentinel.creds
nsc generate creds -a SYS  -n sys     > NATS/sys.creds

# 4. Get the AUTH account signing key (for issuer_account)
nsc describe account AUTH   # note "Signing Keys" section

# 5. Get APP1 account public key
nsc describe account APP1   # note "Account ID" (A...)

# 6. Generate resolver config
nsc generate config --mem-resolver --sys-account SYS > NATS/resolver.conf

# 7. Get the default_sentinel JWT value for the config
nsc describe user -a AUTH -n sentinel --raw
```

**nats-server.conf for decentralized (Scenario B — default_sentinel):**
```conf
# Decentralized AuthCallout — default_sentinel
server_name: authcallout-decentralized
http_port: 8222

include resolver.conf

jetstream { domain: "HUB" }

# Bearer JWT auto-applied to any client/leaf connecting without credentials
default_sentinel: "PASTE_SENTINEL_JWT_HERE"
```

**nats-server.conf for decentralized (Scenario A — explicit sentinel):**
```conf
# Decentralized AuthCallout — explicit sentinel (no default_sentinel)
server_name: authcallout-decentralized-explicit
http_port: 8222

include resolver.conf

jetstream { domain: "HUB" }
# No default_sentinel — clients must load sentinel.creds themselves
```

**config.properties for decentralized:**
```properties
nats.url=nats://localhost:4222
auth.password.backend=kv
kv.bucket.name=auth-users

# Decentralized AuthCallout
auth.decentralized.service.creds=NATS/auth.creds
auth.decentralized.signing.key.path=NATS/nsc_local_repo/keys/A/<signing-key-file>.nk
auth.decentralized.issuer.account=<AUTH_ACCOUNT_PUBLIC_KEY>
auth.decentralized.default.account=<APP1_ACCOUNT_PUBLIC_KEY>

# NKey authentication (Scenario C)
auth.decentralized.nkey.enabled=true
auth.decentralized.nkey.bucket=auth-nkeys

# Explicit sentinel creds (Scenario A only; comment out for default_sentinel)
# auth.decentralized.sentinel.creds=NATS/sentinel.creds
```

**Java DecentralizedAuthCalloutService.java — key differences from centralized:**
- Connect with `Nats.credentials(serviceCredsPath)` not username/password
- Sign JWTs using AUTH account **signing key** (not account key directly)
- Add `userClaim.issuerAccount(issuerAccount)` so server resolves the signing key to AUTH account
- Add `.issuerAccount(issuerAccount)` to `AuthorizationResponse` as well
- Route by `ar.connectOpts.user != null` → password path; else → NKey path (`ar.connectOpts.nkey`)

**Java NatsDecentralizedPasswordClient.java pattern:**
```java
// Scenario A: explicit sentinel
Options opts = new Options.Builder()
    .server(url)
    .authHandler(Nats.credentials(sentinelCredsPath))
    .userInfo(username, password)   // sent alongside sentinel JWT
    .errorListener(new ErrorListener() {}).build();

// Scenario B: default_sentinel (no creds file needed)
Options opts = new Options.Builder()
    .server(url)
    .userInfo(username, password)
    .errorListener(new ErrorListener() {}).build();
```

**Java NatsDecentralizedNKeyClient.java pattern:**
```java
// Scenario C: NKey challenge-response; server applies default_sentinel
Options opts = new Options.Builder()
    .server(url)
    .authHandler(new AuthHandler() {
        public char[] getID()     { return nkey.getPublicKey(); }
        public byte[] sign(byte[] nonce) { return nkey.sign(nonce); }
        public char[] getJWT()    { return null; } // no JWT — server applies default_sentinel
    })
    .errorListener(new ErrorListener() {}).build();
```

**Go decentralized_service.go — key differences:**
```go
// Connect with creds file
nc, _ := nats.Connect(url, nats.UserCredentials("NATS/auth.creds"))

// Build user JWT with issuer_account
kp, _ := nkeys.FromSeed([]byte(signingKeySeed))
pub, _ := kp.PublicKey()

uc := natsjwt.NewUserClaims(ar.UserNkey)
uc.IssuerAccount = issuerAccount   // AUTH account public key
uc.Name = identity
uc.Audience = defaultAccount

userJwt, _ := uc.Encode(kp)

rc := natsjwt.NewAuthorizationResponseClaims(ar.UserNkey)
rc.IssuerAccount = issuerAccount
rc.Audience = ar.ServerID.ID
rc.Jwt = userJwt
responseJwt, _ := rc.Encode(kp)
```

**Go NKey client pattern:**
```go
kp, _ := nkeys.FromSeed([]byte(nkeySeed))
pub, _ := kp.PublicKey()

nc, _ := nats.Connect(url, nats.Nkey(pub, func(nonce []byte) ([]byte, error) {
    return kp.Sign(nonce)
}))
```

**NKey whitelist KV setup:**
```bash
# Create KV bucket for NKey whitelist
nats kv add auth-nkeys --creds NATS/auth.creds -s nats://localhost:4222

# Add a client NKey public key (get from: nk -gen user; note the public key U...)
nats kv put auth-nkeys <CLIENT_PUBLIC_NKEY> "my-service-account" \
  --creds NATS/auth.creds -s nats://localhost:4222
```

---

### Scenario: `leaf-sentinel`

**What it does:** Hub NATS server + separate Leaf Node server. Both use the decentralized operator model.
The Hub has `default_sentinel` configured. Leaf nodes connect to the Hub using only an NKey seed.
The Hub applies default_sentinel automatically and dispatches to `LeafSentinelAuthCalloutService`.

**Critical difference from `decentralized`:** The service performs **Ed25519 nonce signature verification**.
When `default_sentinel` is active, the NATS server skips its native nonce-sig check, so the callout
is the sole enforcer of key possession for both leaf nodes and regular NKey clients.

**Startup order (critical):**
1. Start hub NATS server
2. Start `leaf-sentinel-service` (AuthCallout must be listening before leaf connects)
3. Start leaf NATS server

**nats-server-hub.conf:**
```conf
# Hub for Leaf Node + default_sentinel AuthCallout
server_name: hub-auth-callout
port: 4222
http_port: 8222

include resolver.conf

jetstream { domain: "HUB" }

default_sentinel: "PASTE_SENTINEL_JWT_HERE"

leafnodes {
    listen: 0.0.0.0:7422
}
```

**nats-server-leaf.conf:**
```conf
# Leaf node — authenticates to Hub with NKey only (no JWT)
server_name: leaf-node
port: 4223
http_port: 8223

leafnodes {
    remotes = [
        {
            url: "nats-leaf://localhost:7422"
            # Leaf NKey seed — Hub applies default_sentinel and calls out to auth service
            nkey: "SUABC...REPLACE_WITH_LEAF_NKEY_SEED"
        }
    ]
}
```

**Leaf node NKey setup:**
```bash
# Generate a new NKey for the leaf node
nk -gen server
# Output: seed (SU...) and public key (U...)
# Put seed in nats-server-leaf.conf -> nkey field
# Add public key to auth-nkeys KV bucket:
nats kv put auth-nkeys <LEAF_PUBLIC_KEY> "leaf-node" \
  --creds NATS/auth.creds -s nats://localhost:4222
```

**Java LeafSentinelAuthCalloutService.java — key differences from decentralized:**
- Performs Ed25519 nonce signature verification before KV lookup
- `ar.clientInfo.nonce` = the nonce the server sent the connecting party
- `ar.connectOpts.sig` = Base64url-encoded Ed25519 signature of nonce
- `ar.connectOpts.nkey` = the connecting party's public NKey
- Logs `ar.clientInfo.kind` to distinguish leaf nodes from regular clients

```java
// Nonce verification pattern
NKey pubKey = NKey.fromPublicKey(clientNKey.toCharArray());
byte[] sig = Base64.getUrlDecoder().decode(ar.connectOpts.sig);
boolean valid = pubKey.verify(ar.clientInfo.nonce.getBytes(), sig);
if (!valid) return AuthResult.failure("Nonce signature verification failed");
// Then proceed to KV whitelist lookup
```

**Go nonce verification pattern:**
```go
import "github.com/nats-io/nkeys"

func verifyNonce(nkeyPublic, nonce, sigB64url string) (bool, error) {
    kp, err := nkeys.FromPublicKey(nkeyPublic)
    if err != nil { return false, err }
    sig, err := base64.RawURLEncoding.DecodeString(sigB64url)
    if err != nil { return false, err }
    return kp.Verify([]byte(nonce), sig)
}
```

**config.properties for leaf-sentinel:**
```properties
nats.url=nats://localhost:4222
auth.password.backend=mysql
# or kv if you don't have MySQL

# Decentralized config (same as decentralized scenario)
auth.decentralized.service.creds=NATS/auth.creds
auth.decentralized.signing.key.path=NATS/nsc_local_repo/keys/A/<signing-key>.nk
auth.decentralized.issuer.account=<AUTH_ACCOUNT_PUBLIC_KEY>
auth.decentralized.default.account=<APP1_ACCOUNT_PUBLIC_KEY>

# NKey authentication for leaf nodes
auth.decentralized.nkey.enabled=true
auth.decentralized.nkey.bucket=auth-nkeys
```

**Leaf node NKey client (Java) for testing regular NKey clients against hub:**
Same pattern as `decentralized` Scenario C — `NatsDecentralizedNKeyClient`.

---

## Common Utilities

### Java AuthResult (all scenarios)
```java
public class AuthResult {
    public final boolean success;
    public final String account;
    public final String error;

    private AuthResult(boolean success, String account, String error) {
        this.success = success; this.account = account; this.error = error;
    }
    public static AuthResult success(String account) { return new AuthResult(true, account, null); }
    public static AuthResult failure(String error)   { return new AuthResult(false, null, error); }
    public boolean isSuccess() { return success; }
}
```

### Java complete pom.xml

Generate this as `pom.xml` in the Java output directory.
Include only the dependencies relevant to the selected scenario (omit MySQL block for non-mysql; omit jackson for non-okta).

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.example</groupId>
    <artifactId>authcallout-SCENARIO</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.nats</groupId>
            <artifactId>jnats</artifactId>
            <version>2.25.2</version>
        </dependency>
        <dependency>
            <groupId>io.nats</groupId>
            <artifactId>jwt-java</artifactId>
            <version>2.2.0</version>
        </dependency>
        <dependency>
            <groupId>io.nats</groupId>
            <artifactId>nkeys-java</artifactId>
            <version>2.1.1</version>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>1.5.16</version>
        </dependency>
        <!-- Include for mysql scenario only -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <version>9.1.0</version>
        </dependency>
        <!-- Include for okta-ropc and okta-token scenarios only -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.18.2</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <version>3.6.0</version>
                <configuration>
                    <descriptorRefs>
                        <descriptorRef>jar-with-dependencies</descriptorRef>
                    </descriptorRefs>
                    <archive>
                        <manifest>
                            <mainClass>io.example.authcallout.Main</mainClass>
                        </manifest>
                    </archive>
                    <finalName>authcallout-SCENARIO-1.0.0</finalName>
                    <appendAssemblyId>false</appendAssemblyId>
                </configuration>
                <executions>
                    <execution>
                        <id>make-assembly</id>
                        <phase>package</phase>
                        <goals><goal>single</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

Also generate `src/main/java/io/example/authcallout/` as the Java source root,
and a minimal `src/main/resources/logback.xml`:
```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d{HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
  </appender>
  <root level="INFO"><appender-ref ref="STDOUT"/></root>
</configuration>
```

Also generate a `Main.java` entry point that accepts `service` and `client` as first argument:
```java
package io.example.authcallout;
public class Main {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "service";
        String configPath = args.length > 1 ? args[1] : "config.properties";
        switch (mode) {
            case "service" -> ServiceRunner.run(configPath);
            case "client"  -> ClientRunner.run(args);
            default -> { System.err.println("Usage: java -jar <jar> service|client [config]"); System.exit(1); }
        }
    }
}
```

Replace `SCENARIO` in `pom.xml` `artifactId` and `finalName` with the actual use-case slug (e.g. `kv-store`).

### Go complete go.mod

Generate this as `go.mod` in the Go output directory.
Include `github.com/go-sql-driver/mysql` only for the `mysql` scenario.

```
module example.com/authcallout-SCENARIO

go 1.21

require (
    github.com/nats-io/nats.go  v1.37.0
    github.com/nats-io/jwt/v2   v2.7.3
    github.com/nats-io/nkeys    v0.4.8
    github.com/go-sql-driver/mysql v1.7.1
)
```

Also generate a `Makefile` in the Go output directory:
```makefile
.PHONY: build run-service run-client

build:
	go mod tidy && go build -o authcallout-SCENARIO ./...

run-service: build
	SIGNING_KEY_SEED=$(SIGNING_KEY_SEED) ./authcallout-SCENARIO service

run-client: build
	./authcallout-SCENARIO client $(ARGS)
```

Replace `SCENARIO` in `go.mod` module name and `Makefile` with the actual use-case slug.

### NKey generation (CLI reference)
```bash
# Account NKey (for signing key in centralized mode)
nk -gen account
# Output: seed SA..., public key A...

# User NKey (for NKey client)
nk -gen user
# Output: seed SU..., public key U...

# Derive public key from seed
nk -inkey /dev/stdin -pubout <<< "SU..."
```

---

## README Template

Generate a `README.md` for the chosen scenario that includes:

1. **Overview** — one paragraph describing what the scenario does and why
2. **Prerequisites** — tools needed (nats-server, nats CLI, nk, nsc if decentralized, Java 17+/Go 1.21+, Maven if Java)
3. **Configuration** — the placeholders that must be filled before running:
   - Java: `REPLACE_WITH_AUTH_ACCOUNT_SIGNING_KEY_PUBLIC` in `nats-server.conf`
   - Java: `REPLACE_WITH_SA_SEED` in `config.properties`
   - instructions to generate them with `nk -gen account`
4. **Build** — how the artifact was compiled and where it lives:
   - Java: `mvn package -f pom.xml` → `target/authcallout-<scenario>-1.0.0.jar`
   - Go: `go mod tidy && go build -o authcallout-<scenario> ./...`
5. **Startup sequence** — numbered steps with exact commands including how to run the compiled artifact:
   - Java: `java -jar target/authcallout-<scenario>-1.0.0.jar service config.properties`
   - Go: `./authcallout-<scenario> service`
6. **Testing** — NATS CLI commands to test success and failure cases:
   ```bash
   # Success
   nats pub test "hello" --user alice --password alice123 -s nats://localhost:4222
   # Failure (wrong password)
   nats pub test "hello" --user alice --password wrongpassword -s nats://localhost:4222
   ```
7. **Troubleshooting** — common errors and fixes:
   - `"not authorized"` → signing key mismatch between `nats-server.conf` issuer and `config.properties` seed
   - `"KV bucket not found"` → run `setup.sh` before starting the service
   - `"connection refused"` → NATS server not started, or wrong port
   - Leaf node: `"leaf port conflict"` → set explicit `port: 4223` in `nats-server-leaf.conf`
   - Decentralized: `"not valid"` → `default_sentinel` JWT in config doesn't match nsc store (regenerate with `nsc describe user -a AUTH -n sentinel --raw`)
