package io.synadia.authcallout.client;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.ErrorListener;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Decentralized NATS client for Scenarios 1 and 2 — user/pass authentication
 * in a decentralized (JWT-based) server configuration.
 *
 * Scenario 1 (explicit sentinel): The client loads a sentinel .creds file
 * (AUTH account user JWT + NKey seed) AND provides username/password.
 * The server uses the sentinel credentials to route to the AuthCallout service,
 * which then validates the username/password against the KV store.
 * Use this when the server does NOT have default_sentinel configured.
 *
 * Scenario 2 (default_sentinel): The client provides only username/password.
 * The server automatically applies the sentinel from its configuration.
 * Omit the sentinelCredsPath (pass null) for this scenario.
 *
 * Configuration:
 *   auth.decentralized.sentinel.creds   path to the sentinel .creds file (Scenario 1 only)
 */
public class NatsDecentralizedPasswordClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NatsDecentralizedPasswordClient.class);

    private final String natsUrl;
    private final String username;
    private final String password;
    private final String sentinelCredsPath;  // null for Scenario 2 (default_sentinel)

    private Connection connection;

    /**
     * @param sentinelCredsPath path to the sentinel .creds file, or {@code null} for
     *                          Scenario 2 where the server applies default_sentinel automatically
     */
    public NatsDecentralizedPasswordClient(String natsUrl, String username, String password,
                                           String sentinelCredsPath) {
        this.natsUrl = natsUrl;
        this.username = username;
        this.password = password;
        this.sentinelCredsPath = sentinelCredsPath;
    }

    // ── Connection ────────────────────────────────────────────────────────────

    public void connect() throws Exception {
        Options.Builder builder = new Options.Builder()
                .server(natsUrl)
                .userInfo(username, password)
                .maxReconnects(0)
                .errorListener(new ErrorListener() {
                    @Override
                    public void errorOccurred(io.nats.client.Connection conn, String error) {
                        log.warn("[CLIENT] Server error for user '{}': {}", username, error);
                        System.err.printf("[CLIENT] Server error for user '%s': %s%n", username, error);
                    }
                });

        if (sentinelCredsPath != null && !sentinelCredsPath.isBlank()) {
            // Scenario 1: present sentinel credentials alongside user/pass
            builder.authHandler(Nats.credentials(sentinelCredsPath));
            log.info("Connecting as decentralized user '{}' with explicit sentinel @ {}", username, natsUrl);
        } else {
            // Scenario 2: server applies default_sentinel automatically
            log.info("Connecting as decentralized user '{}' (default_sentinel) @ {}", username, natsUrl);
        }

        connection = Nats.connect(builder.build());
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
            log.info("Disconnected from NATS (decentralized user: {})", username);
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public String getUsername() {
        return username;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void ensureConnected() throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Not connected — call connect() first");
        }
    }
}
