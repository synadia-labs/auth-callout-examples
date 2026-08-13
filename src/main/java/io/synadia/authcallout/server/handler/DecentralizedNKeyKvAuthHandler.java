package io.synadia.authcallout.server.handler;

import io.nats.client.Connection;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.jwt.UserClaim;
import io.synadia.authcallout.model.AuthResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Decentralized NKey authentication handler backed by a NATS KV store.
 *
 * Used in Scenario 3 (decentralized NKey + default_sentinel).
 *
 * The KV bucket stores authorized NKey public keys as keys, with an
 * arbitrary non-empty string value (e.g. a label or role description).
 * If the connecting client's public NKey is present in the bucket,
 * authentication succeeds.
 *
 * KV bucket schema:
 *   key   → <NKey public key string>
 *   value → <arbitrary label, e.g. "authorized" or "service-account-1">
 *
 * Configuration:
 *   auth.decentralized.nkey.bucket   e.g. auth-nkeys (default)
 */
public class DecentralizedNKeyKvAuthHandler implements NKeyAuthHandler {

    private static final Logger log = LoggerFactory.getLogger(DecentralizedNKeyKvAuthHandler.class);

    private final KeyValue kv;
    private final String defaultAccount;

    public DecentralizedNKeyKvAuthHandler(Connection nc, String bucketName, String defaultAccount)
            throws IOException, JetStreamApiException {
        this.kv = nc.keyValue(bucketName);
        this.defaultAccount = defaultAccount;
        log.info("DecentralizedNKeyKvAuthHandler ready — bucket: {}", bucketName);
    }

    @Override
    public AuthResult authenticate(String nkeyPublic) {
        if (nkeyPublic == null || nkeyPublic.isBlank()) {
            return AuthResult.failure("NKey is required");
        }
        try {
            KeyValueEntry entry = kv.get(nkeyPublic);
            if (entry != null && entry.getValue() != null && entry.getValue().length > 0) {
                String label = new String(entry.getValue());
                log.debug("NKey authorized — nkey: {}, label: {}", nkeyPublic, label);
                return AuthResult.success(defaultAccount, new UserClaim());
            }
            log.debug("NKey not found in KV store — nkey: {}", nkeyPublic);
            return AuthResult.failure("NKey not authorized");
        } catch (Exception e) {
            log.error("KV lookup failed for nkey: {}", nkeyPublic, e);
            return AuthResult.failure("NKey authorization error");
        }
    }

    @Override
    public void close() {
        // KeyValue is backed by the shared JetStream context; nothing to release here
    }
}
