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
 * Demo client connecting with username/password.
 * Usage: java -jar <jar> client [natsUrl] [username] [password] [subject] [message]
 */
public class ClientRunner {

    private static final Logger log = LoggerFactory.getLogger(ClientRunner.class);

    public static void run(String[] args) throws Exception {
        String natsUrl  = at(args, 1, "nats://localhost:4222");
        String username = at(args, 2, "alice");
        String password = at(args, 3, "alice123");
        String subject  = at(args, 4, "test");
        String message  = at(args, 5, "hello from " + at(args, 2, "alice"));

        Options opts = new Options.Builder()
                .server(natsUrl).userInfo(username, password)
                .maxReconnects(0).reconnectWait(Duration.ofSeconds(1))
                .errorListener(new ErrorListener() {}).build();

        try (Connection nc = Nats.connect(opts)) {
            System.out.printf("[CLIENT] Connected as '%s'%n", username);
            nc.createDispatcher(msg ->
                System.out.printf("[CLIENT] Received on '%s': %s%n",
                        msg.getSubject(), new String(msg.getData(), StandardCharsets.UTF_8))
            ).subscribe(subject);
            nc.publish(subject, message.getBytes(StandardCharsets.UTF_8));
            System.out.printf("[CLIENT] Published '%s' to '%s'%n", message, subject);
            Thread.sleep(500);
        } catch (io.nats.client.AuthenticationException e) {
            System.err.printf("[CLIENT] Authentication failed for '%s'%n", username);
            System.exit(1);
        }
    }

    private static String at(String[] a, int i, String def) {
        return (a != null && a.length > i) ? a[i] : def;
    }
}
