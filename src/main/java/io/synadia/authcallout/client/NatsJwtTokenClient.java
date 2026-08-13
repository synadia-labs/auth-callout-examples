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
 * NATS client that authenticates with an auth token (e.g. an Okta access token).
 *
 * Used in Scenario 4 where the NATS server passes the token to the AuthCallout
 * service, which validates it against Okta's introspection endpoint.
 *
 * The token is passed as a NATS auth token (CONNECT opts.auth_token).
 */
public class NatsJwtTokenClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NatsJwtTokenClient.class);

    private final String natsUrl;
    private final String token;

    private Connection connection;

    public NatsJwtTokenClient(String natsUrl, String token) {
        this.natsUrl = natsUrl;
        this.token = token;
    }

    // ── Connection ────────────────────────────────────────────────────────────

    public void connect() throws Exception {
        Options options = new Options.Builder()
                .server(natsUrl)
                .token(token.toCharArray())
                .maxReconnects(3)
                .reconnectWait(Duration.ofSeconds(1))
                .errorListener(new ErrorListener() {})
                .build();

        connection = Nats.connect(options);
        log.info("Connected to NATS with token @ {}", natsUrl);
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
            log.info("Disconnected from NATS (token client)");
        }
    }

    public Connection getConnection() {
        return connection;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void ensureConnected() throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Not connected — call connect() first");
        }
    }
}
