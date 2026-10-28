package io.example.authcallout;

import io.nats.client.Connection;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates a client by its NKey public key against a NATS KV whitelist.
 * KV schema: key = NKey public key (U...), value = arbitrary label.
 */
public class NKeyKvAuthHandler {
    private static final Logger log = LoggerFactory.getLogger(NKeyKvAuthHandler.class);
    private final KeyValue kv;
    private final String defaultAccount;

    public NKeyKvAuthHandler(Connection nc, String bucketName, String defaultAccount) throws Exception {
        this.kv = nc.keyValue(bucketName);
        this.defaultAccount = defaultAccount;
        log.info("NKeyKvAuthHandler ready — bucket: {}", bucketName);
    }

    public AuthResult authenticate(String nkeyPublic) {
        if (nkeyPublic == null || nkeyPublic.isBlank()) return AuthResult.failure("NKey is required");
        try {
            KeyValueEntry entry = kv.get(nkeyPublic);
            if (entry != null && entry.getValue() != null && entry.getValue().length > 0) {
                log.debug("NKey authorized: {}", nkeyPublic);
                return AuthResult.success(defaultAccount);
            }
            log.debug("NKey not in whitelist: {}", nkeyPublic);
            return AuthResult.failure("NKey not authorized");
        } catch (Exception e) {
            log.error("NKey KV lookup error for {}", nkeyPublic, e);
            return AuthResult.failure("NKey authorization error");
        }
    }
}
