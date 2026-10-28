package io.example.authcallout;

import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Demo client that connects to NATS using username/password.
 * The NATS server delegates credential validation to the AuthCallout service.
 *
 * Usage:
 *   java -jar authcallout-kv-store-1.0.0.jar client [natsUrl] [username] [password] [subject] [message]
 *
 * Defaults:
 *   natsUrl  = nats://localhost:4222
 *   username = alice
 *   password = alice123
 *   subject  = test
 *   message  = hello from alice
 */
public class ClientRunner {

    private static final Logger log = LoggerFactory.getLogger(ClientRunner.class);

    public static void run(String[] args) throws Exception {
        // args[0] = "client", remaining are optional positional params
        String natsUrl  = argAt(args, 1, "nats://localhost:4222");
        String username = argAt(args, 2, "alice");
        String password = argAt(args, 3, "alice123");
        String subject  = argAt(args, 4, "test");
        String message  = argAt(args, 5, "hello from " + argAt(args, 2, "alice"));

        log.info("Connecting as '{}' to {}", username, natsUrl);

        Options opts = new Options.Builder()
                .server(natsUrl)
                .userInfo(username, password)
                .maxReconnects(0)
                .reconnectWait(Duration.ofSeconds(1))
                .errorListener(new ErrorListener() {})
                .build();

        try (Connection nc = Nats.connect(opts)) {
            System.out.printf("[CLIENT] Connected as '%s'%n", username);

            nc.createDispatcher(msg -> {
                String body = new String(msg.getData(), StandardCharsets.UTF_8);
                System.out.printf("[CLIENT] Received on '%s': %s%n", msg.getSubject(), body);
            }).subscribe(subject);

            nc.publish(subject, message.getBytes(StandardCharsets.UTF_8));
            System.out.printf("[CLIENT] Published '%s' to '%s'%n", message, subject);

            Thread.sleep(500);
        } catch (io.nats.client.AuthenticationException e) {
            System.err.printf("[CLIENT] Authentication failed for user '%s' — check server logs%n", username);
            log.error("Authentication failed", e);
            System.exit(1);
        }
    }

    private static String argAt(String[] args, int i, String def) {
        return (args != null && args.length > i) ? args[i] : def;
    }
}
