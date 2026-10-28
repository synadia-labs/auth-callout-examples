package io.example.authcallout;

import io.nats.client.*;
import io.nats.nkey.NKey;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * NKey client for testing against the hub (same as decentralized Scenario C).
 * The hub applies default_sentinel; the service verifies the nonce signature.
 */
public class NatsNKeyClient implements AutoCloseable {
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
        connection = Nats.connect(new Options.Builder()
                .server(natsUrl)
                .authHandler(new AuthHandler() {
                    @Override public char[] getID() {
                        try { return nkey.getPublicKey(); } catch (Exception e) { throw new RuntimeException(e); }
                    }
                    @Override public byte[] sign(byte[] nonce) {
                        try { return nkey.sign(nonce); } catch (Exception e) { throw new RuntimeException(e); }
                    }
                    @Override public char[] getJWT() { return null; }
                })
                .maxReconnects(0).reconnectWait(Duration.ofSeconds(1))
                .errorListener(new ErrorListener() {}).build());
    }

    public void publish(String subject, String msg) throws Exception {
        connection.publish(subject, msg.getBytes(StandardCharsets.UTF_8));
    }
    public void subscribe(String subject) throws Exception {
        connection.createDispatcher(msg -> System.out.printf("[NKEY] Received '%s': %s%n",
                msg.getSubject(), new String(msg.getData(), StandardCharsets.UTF_8))).subscribe(subject);
    }
    public String getPublicKey() { return publicKey; }
    @Override public void close() throws Exception {
        if (connection != null) connection.close();
        nkey.clear();
    }
}
