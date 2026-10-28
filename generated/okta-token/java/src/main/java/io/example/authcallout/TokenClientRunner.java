package io.example.authcallout;

import io.nats.client.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Demo client that connects using an Okta access token (not username/password).
 * The token is set as the NATS auth_token in the CONNECT options.
 *
 * Usage: java -jar <jar> client [natsUrl] [accessToken] [subject] [message]
 *
 * Obtain an access token first via Okta (e.g. client_credentials flow):
 *   curl -s -X POST https://your-org.okta.com/oauth2/v1/token \
 *     -H 'Content-Type: application/x-www-form-urlencoded' \
 *     -d 'grant_type=client_credentials&client_id=...&client_secret=...&scope=openid' \
 *     | jq -r .access_token
 */
public class TokenClientRunner {

    public static void run(String[] args) throws Exception {
        String url     = at(args,1,"nats://localhost:4222");
        String token   = at(args,2,"REPLACE_WITH_OKTA_ACCESS_TOKEN");
        String subject = at(args,3,"test");
        String message = at(args,4,"hello from token client");

        Options opts = new Options.Builder()
                .server(url)
                .token(token.toCharArray())
                .maxReconnects(0)
                .reconnectWait(Duration.ofSeconds(1))
                .errorListener(new ErrorListener() {})
                .build();

        try (Connection nc = Nats.connect(opts)) {
            System.out.println("[CLIENT] Connected with access token");
            nc.createDispatcher(msg -> System.out.printf("[CLIENT] Received on '%s': %s%n",
                    msg.getSubject(), new String(msg.getData(), StandardCharsets.UTF_8))
            ).subscribe(subject);
            nc.publish(subject, message.getBytes(StandardCharsets.UTF_8));
            System.out.printf("[CLIENT] Published '%s' to '%s'%n", message, subject);
            Thread.sleep(500);
        } catch (AuthenticationException e) {
            System.err.println("[CLIENT] Token rejected — check Okta introspection config");
            System.exit(1);
        }
    }

    private static String at(String[] a, int i, String d) { return a!=null&&a.length>i?a[i]:d; }
}
