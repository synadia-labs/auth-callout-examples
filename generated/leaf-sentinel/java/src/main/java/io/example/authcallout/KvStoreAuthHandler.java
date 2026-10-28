package io.example.authcallout;

import io.nats.client.Connection;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class KvStoreAuthHandler {
    private static final Logger log = LoggerFactory.getLogger(KvStoreAuthHandler.class);
    private final KeyValue kv;
    private final String defaultAccount;

    public KvStoreAuthHandler(Connection nc, String bucketName, String defaultAccount) throws Exception {
        this.kv = nc.keyValue(bucketName);
        this.defaultAccount = defaultAccount;
        log.info("KvStoreAuthHandler ready — bucket: {}", bucketName);
    }

    public AuthResult authenticate(String username, String password) {
        if (username == null || password == null) return AuthResult.failure("Missing credentials");
        try {
            KeyValueEntry entry = kv.get(username);
            if (entry == null || entry.getValue() == null) return AuthResult.failure("User not found");
            String stored = new String(entry.getValue(), StandardCharsets.UTF_8).trim();
            if (stored.equalsIgnoreCase(sha256Hex(password))) return AuthResult.success(defaultAccount);
            return AuthResult.failure("Invalid password");
        } catch (Exception e) { return AuthResult.failure("Auth error: " + e.getMessage()); }
    }

    static String sha256Hex(String input) throws NoSuchAlgorithmException {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        byte[] h = d.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
