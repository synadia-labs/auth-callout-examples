package io.example.authcallout;

import io.nats.client.*;
import io.nats.nkey.NKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * NKey challenge-response client for Scenario C.
 * Connects to NATS using an NKey seed. The server applies default_sentinel
 * automatically and the AuthCallout service validates the NKey against
 * the auth-nkeys KV whitelist.
 */
public class NatsNKeyClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NatsNKeyClient.class);

    private final String natsUrl;
    private final NKey   nkey;
    private final String publicKey;
    private Connection   connection;

    public NatsNKeyClient(String natsUrl, String nkeySeed) throws Exception {
        this.natsUrl   = natsUrl;
        this.nkey      = NKey.fromSeed(nkeySeed.toCharArray());
        this.publicKey = new String(this.nkey.getPublicKey());
    }

    public void connect() throws Exception {
        Options opts = new Options.Builder()
                .server(natsUrl)
                .authHandler(new AuthHandler() {
                    @Override public char[] getID() {
                        try { return nkey.getPublicKey(); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }
                    @Override public byte[] sign(byte[] nonce) {
                        try { return nkey.sign(nonce); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }
                    @Override public char[] getJWT() { return null; } // server applies default_sentinel
                })
                .maxReconnects(0).reconnectWait(Duration.ofSeconds(1))
                .errorListener(new ErrorListener() {}).build();

        connection = Nats.connect(opts);
        log.info("Connected via NKey (public: {}) @ {}", publicKey, natsUrl);
    }

    public void publish(String subject, String message) throws Exception {
        connection.publish(subject, message.getBytes(StandardCharsets.UTF_8));
    }

    public void subscribe(String subject) throws Exception {
        connection.createDispatcher(msg ->
            System.out.printf("[NKEY CLIENT] Received on '%s': %s%n",
                    msg.getSubject(), new String(msg.getData(), StandardCharsets.UTF_8))
        ).subscribe(subject);
    }

    public String getPublicKey() { return publicKey; }

    @Override public void close() throws Exception {
        if (connection != null) connection.close();
        nkey.clear();
    }
}
