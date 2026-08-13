package io.synadia.authcallout.server.handler;

import io.nats.client.Connection;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueWatchOption;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.jwt.UserClaim;
import io.synadia.authcallout.model.AuthResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Scenario 1: client username/password → NATS KV Store.
 *
 * Each entry in the KV bucket maps:
 *   key   = username
 *   value = SHA-256 hex of the password
 *
 * To add a user (example using nats CLI):
 *   nats kv put auth-users alice $(echo -n "alicepassword" | sha256sum | awk '{print $1}')
 */
public class KvStoreAuthHandler implements PasswordAuthHandler {

    private static final Logger log = LoggerFactory.getLogger(KvStoreAuthHandler.class);

    private final KeyValue kv;
    private final String defaultAccount;

    public KvStoreAuthHandler(Connection nc, String bucketName, String defaultAccount)
            throws IOException, JetStreamApiException {
        this.kv = nc.keyValue(bucketName);
        this.defaultAccount = defaultAccount;
        log.info("KvStoreAuthHandler ready — bucket: {}", bucketName);
    }

    @Override
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

            String storedHash = new String(entry.getValue(), StandardCharsets.UTF_8).trim();
            String inputHash = sha256Hex(password);

            if (storedHash.equalsIgnoreCase(inputHash)) {
                log.debug("KV auth: user '{}' authenticated", username);
                return AuthResult.success(defaultAccount, new UserClaim());
            }

            log.debug("KV auth: invalid password for user '{}'", username);
            return AuthResult.failure("Invalid password");

        } catch (Exception e) {
            log.error("KV auth error for user '{}'", username, e);
            return AuthResult.failure("Authentication service error");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static String sha256Hex(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
