package io.synadia.authcallout.client;

import io.nats.client.AuthHandler;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.ErrorListener;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.Subscription;
import io.nats.nkey.NKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Decentralized NATS client for Scenario 3 — NKey challenge-response authentication
 * with {@code default_sentinel} configured on the server.
 *
 * The client holds an NKey seed (private key). On connect, the NATS server issues
 * a nonce; the client signs it with its private NKey and sends back the public key
 * and signature. The server applies the default_sentinel automatically, routing the
 * request to the AuthCallout service, which looks up the client's public NKey in
 * the KV whitelist bucket.
 *
 * Note: A client cannot present a sentinel JWT AND perform NKey challenge-response
 * simultaneously — they are mutually exclusive NATS CONNECT mechanisms. The
 * default_sentinel server configuration is therefore required for this scenario.
 *
 * Configuration:
 *   auth.decentralized.nkey.seed   the NKey seed for this client (SUABC...)
 */
public class NatsDecentralizedNKeyClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NatsDecentralizedNKeyClient.class);

    private final String natsUrl;
    private final NKey nkey;
    private final String publicKey;

    private Connection connection;

    public NatsDecentralizedNKeyClient(String natsUrl, String nkeySeed) throws Exception {
        this.natsUrl = natsUrl;
        this.nkey = NKey.fromSeed(nkeySeed.toCharArray());
        this.publicKey = new String(this.nkey.getPublicKey());
    }

    // ── Connection ────────────────────────────────────────────────────────────

    public void connect() throws Exception {
        Options options = new Options.Builder()
                .server(natsUrl)
                .authHandler(buildAuthHandler())
                .maxReconnects(3)
                .reconnectWait(Duration.ofSeconds(1))
                .errorListener(new ErrorListener() {})
                .build();

        connection = Nats.connect(options);
        log.info("Connected to NATS via NKey challenge-response (public: {}) @ {}", publicKey, natsUrl);
    }

    public boolean isConnected() {
        return connection != null
                && connection.getStatus() == Connection.Status.CONNECTED;
    }

    // ── Publish ───────────────────────────────────────────────────────────────

    public void publish(String subject, String message) throws Exception {
        ensureConnected();
        connection.publish(subject, message.getBytes(StandardCharsets.UTF_8));
        log.debug("Published to '{}': {}", subject, message);
    }

    public void publish(String subject, byte[] data) throws Exception {
        ensureConnected();
        connection.publish(subject, data);
        log.debug("Published {} bytes to '{}'", data.length, subject);
    }

    // ── Request/Reply ─────────────────────────────────────────────────────────

    public String request(String subject, String body, long timeoutMs) throws Exception {
        ensureConnected();
        Message reply = connection.request(subject,
                body.getBytes(StandardCharsets.UTF_8),
                Duration.ofMillis(timeoutMs));
        if (reply == null) {
            throw new RuntimeException("Request timed out on subject: " + subject);
        }
        return new String(reply.getData(), StandardCharsets.UTF_8);
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────

    public Subscription subscribe(String subject) throws Exception {
        ensureConnected();
        return connection.subscribe(subject);
    }

    public Dispatcher createDispatcher(String subject, Consumer<Message> handler) throws Exception {
        ensureConnected();
        Dispatcher dispatcher = connection.createDispatcher(msg -> {
            log.debug("Received message on '{}'", msg.getSubject());
            handler.accept(msg);
        });
        dispatcher.subscribe(subject);
        return dispatcher;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() throws Exception {
        if (connection != null) {
            connection.close();
            log.info("Disconnected from NATS (NKey client: {})", publicKey);
        }
        nkey.clear();
    }

    public Connection getConnection() {
        return connection;
    }

    public String getPublicKey() {
        return publicKey;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void ensureConnected() throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Not connected — call connect() first");
        }
    }

    /**
     * Builds a NATS AuthHandler that performs NKey challenge-response.
     * Returns no JWT — the server applies default_sentinel automatically.
     */
    private AuthHandler buildAuthHandler() {
        return new AuthHandler() {
            @Override
            public char[] getID() {
                try {
                    return nkey.getPublicKey();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to get NKey public key", e);
                }
            }

            @Override
            public byte[] sign(byte[] nonce) {
                try {
                    return nkey.sign(nonce);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to sign nonce with NKey", e);
                }
            }

            @Override
            public char[] getJWT() {
                return null;  // no JWT — server applies default_sentinel
            }
        };
    }
}
