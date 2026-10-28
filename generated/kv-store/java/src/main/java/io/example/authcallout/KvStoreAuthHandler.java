package io.example.authcallout;

import io.nats.client.Connection;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Authenticates clients by looking up their username in a NATS KV bucket
 * and comparing SHA-256(password) against the stored hash.
 *
 * KV bucket schema:
 *   key   = username
 *   value = SHA-256 hex of the password
 *
 * Add a user via NATS CLI:
 *   nats kv put auth-users alice $(echo -n "alice123" | sha256sum | awk '{print $1}') \
 *       --user auth --password auth -s nats://localhost:4222
 */
public class KvStoreAuthHandler {

    private static final Logger log = LoggerFactory.getLogger(KvStoreAuthHandler.class);

    private final KeyValue kv;
    private final String   defaultAccount;

    public KvStoreAuthHandler(Connection nc, String bucketName, String defaultAccount) throws Exception {
        this.kv             = nc.keyValue(bucketName);
        this.defaultAccount = defaultAccount;
        log.info("KvStoreAuthHandler ready — bucket: {}", bucketName);
    }

    public AuthResult authenticate(String username, String password) {
        if (username == null || password == null) {
            return AuthResult.failure("Username and password are required");
        }
        try {
            KeyValueEntry entry = kv.get(username);
            if (entry == null || entry.getValue() == null) {
                log.debug("KV auth: user '{}' not found", username);
                return AuthResult.failure("User not found: " + username);
            }
            String stored = new String(entry.getValue(), StandardCharsets.UTF_8).trim();
            String input  = sha256Hex(password);
            if (stored.equalsIgnoreCase(input)) {
                log.debug("KV auth: user '{}' authenticated", username);
                return AuthResult.success(defaultAccount);
            }
            log.debug("KV auth: wrong password for user '{}'", username);
            return AuthResult.failure("Invalid password");
        } catch (Exception e) {
            log.error("KV auth error for user '{}'", username, e);
            return AuthResult.failure("Authentication service error");
        }
    }

    static String sha256Hex(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
