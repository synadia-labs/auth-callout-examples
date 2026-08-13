package io.synadia.authcallout.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AppConfig {

    private final Properties props;

    private AppConfig(Properties props) {
        this.props = props;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static AppConfig loadFromFile(String path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            p.load(in);
        }
        return new AppConfig(p);
    }

    public static AppConfig loadFromClasspath() throws IOException {
        Properties p = new Properties();
        try (InputStream in = AppConfig.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (in == null) {
                throw new IOException("config.properties not found on classpath");
            }
            p.load(in);
        }
        return new AppConfig(p);
    }

    // ── NATS ──────────────────────────────────────────────────────────────────

    public String getNatsUrl() {
        return props.getProperty("nats.url", "nats://localhost:4222");
    }

    public String getServiceUser() {
        return props.getProperty("nats.service.user");
    }

    public String getServicePassword() {
        return props.getProperty("nats.service.password");
    }

    // ── Signing Key ───────────────────────────────────────────────────────────

    public String getSigningKeySeed() {
        return require("auth.signing.key.seed");
    }

    /** Returns the Curve25519 private seed for xkey decryption, or {@code null} if not configured. */
    public String getXkeySeed() {
        String v = props.getProperty("auth.xkey.seed");
        return (v == null || v.isBlank()) ? null : v;
    }

    public String getDefaultAccount() {
        return props.getProperty("auth.default.account", "APP");
    }

    // ── Backend Selection ─────────────────────────────────────────────────────

    /** Returns "kv", "mysql", or "okta". */
    public String getPasswordBackend() {
        return props.getProperty("auth.password.backend", "kv");
    }

    // ── KV Store ──────────────────────────────────────────────────────────────

    public String getKvBucketName() {
        return props.getProperty("kv.bucket.name", "auth-users");
    }

    // ── MySQL ─────────────────────────────────────────────────────────────────

    public String getMysqlUrl() {
        return require("mysql.url");
    }

    public String getMysqlUser() {
        return require("mysql.user");
    }

    public String getMysqlPassword() {
        return require("mysql.password");
    }

    public String getMysqlQuery() {
        return props.getProperty("mysql.query",
                "SELECT password_hash FROM users WHERE username = ?");
    }

    // ── Okta ──────────────────────────────────────────────────────────────────

    public String getOktaDomain() {
        return require("okta.domain");
    }

    public String getOktaClientId() {
        return require("okta.client.id");
    }

    public String getOktaClientSecret() {
        return require("okta.client.secret");
    }

    public String getOktaScopes() {
        return props.getProperty("okta.scopes", "openid profile email");
    }

    /** Token endpoint path. Default is Okta's path; set to /oauth/token for Auth0. */
    public String getOktaTokenPath() {
        return props.getProperty("okta.token.path", "/oauth2/v1/token");
    }

    /** Introspect endpoint path. Default is Okta's path; set to /oauth/introspect for Auth0. */
    public String getOktaIntrospectPath() {
        return props.getProperty("okta.introspect.path", "/oauth2/v1/introspect");
    }

    // ── Decentralized AuthCallout ─────────────────────────────────────────────

    /** Reads the AUTH account signing key seed from the .nk file specified in config. */
    public String getDecentralizedSigningKeySeed() throws IOException {
        String path = require("auth.decentralized.signing.key.path");
        return Files.readString(Path.of(path)).strip();
    }

    /** Whether NKey authentication is enabled (Scenario 3). Default: false. */
    public boolean isDecentralizedNKeyEnabled() {
        return Boolean.parseBoolean(props.getProperty("auth.decentralized.nkey.enabled", "false"));
    }

    /** KV bucket name for the NKey whitelist (Scenario 3). Default: auth-nkeys. */
    public String getDecentralizedNKeyBucket() {
        return props.getProperty("auth.decentralized.nkey.bucket", "auth-nkeys");
    }

    /**
     * Path to the sentinel .creds file (Scenario 1 — explicit sentinel).
     * Returns null if not configured (Scenario 2 uses default_sentinel server-side).
     */
    public String getDecentralizedSentinelCreds() {
        String v = props.getProperty("auth.decentralized.sentinel.creds");
        return (v == null || v.isBlank()) ? null : v;
    }

    /** NKey seed for the decentralized NKey client (Scenario 3). */
    public String getDecentralizedNKeySeed() {
        return require("auth.decentralized.nkey.seed");
    }

    /** Path to the authcallout service user .creds file (AUTH account user). */
    public String getDecentralizedServiceCreds() {
        return require("auth.decentralized.service.creds");
    }

    /**
     * AUTH account public key — the issuer_account value for JWTs signed by the AUTH signing key.
     * Required in operator mode so the server can resolve the signing key back to its account.
     */
    public String getDecentralizedIssuerAccount() {
        return require("auth.decentralized.issuer.account");
    }

    /**
     * Account public key where authenticated users are placed (decentralized mode).
     * Must be a NATS account NKey public key (starts with 'A') when using operator mode.
     */
    public String getDecentralizedDefaultAccount() {
        return require("auth.decentralized.default.account");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String require(String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required config key missing: " + key);
        }
        return value;
    }

    public String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }
}
