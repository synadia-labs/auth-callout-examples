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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * NATS client that authenticates with username and password.
 *
 * Used in Scenarios 1, 2, and 3 where the NATS server delegates
 * credential validation to the AuthCallout service.
 */
public class NatsUserPasswordClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NatsUserPasswordClient.class);

    private final String natsUrl;
    private final String username;
    private final String password;

    private Connection connection;

    public NatsUserPasswordClient(String natsUrl, String username, String password) {
        this.natsUrl = natsUrl;
        this.username = username;
        this.password = password;
    }

    // ── Connection ────────────────────────────────────────────────────────────

    public void connect() throws Exception {
        Options options = new Options.Builder()
                .server(natsUrl)
                .userInfo(username, password)
                .maxReconnects(3)
                .reconnectWait(Duration.ofSeconds(1))
                .errorListener(new ErrorListener() {})
                .build();

        connection = Nats.connect(options);
        log.info("Connected to NATS as user '{}' @ {}", username, natsUrl);
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
        Subscription sub = connection.subscribe(subject);
        log.debug("Subscribed to '{}'", subject);
        return sub;
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
            log.info("Disconnected from NATS (user: {})", username);
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
