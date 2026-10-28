package io.example.authcallout;

import io.nats.client.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Demo clients for the decentralized scenario.
 *
 * Usage:
 *   java -jar <jar> client password [natsUrl] [username] [password] [subject]
 *   java -jar <jar> client nkey     [natsUrl] [nkeySeed]            [subject]
 */
public class ClientRunner {

    public static void run(String[] args) throws Exception {
        // args[0]="client", args[1]=submode (password|nkey), rest=params
        String submode = at(args, 1, "password");
        switch (submode) {
            case "password" -> runPassword(args);
            case "nkey"     -> runNKey(args);
            default -> {
                System.err.println("Usage: client password|nkey [options]");
                System.exit(1);
            }
        }
    }

    private static void runPassword(String[] args) throws Exception {
        String url      = at(args,2,"nats://localhost:4222");
        String username = at(args,3,"alice");
        String password = at(args,4,"alice123");
        String subject  = at(args,5,"test");

        // Scenario B: default_sentinel — just user/pass, no sentinel creds needed
        Options opts = new Options.Builder()
                .server(url).userInfo(username,password).maxReconnects(0)
                .reconnectWait(Duration.ofSeconds(1)).errorListener(new ErrorListener(){}).build();

        try (Connection nc = Nats.connect(opts)) {
            System.out.printf("[CLIENT] Connected as '%s' (default_sentinel)%n",username);
            nc.createDispatcher(msg->System.out.printf("[CLIENT] Received: %s%n",
                    new String(msg.getData(),StandardCharsets.UTF_8))).subscribe(subject);
            nc.publish(subject,("hello from "+username).getBytes(StandardCharsets.UTF_8));
            System.out.printf("[CLIENT] Published to '%s'%n",subject);
            Thread.sleep(500);
        } catch (AuthenticationException e) {
            System.err.printf("[CLIENT] Auth failed for '%s'%n",username); System.exit(1);
        }
    }

    private static void runNKey(String[] args) throws Exception {
        String url     = at(args,2,"nats://localhost:4222");
        String seed    = at(args,3,"REPLACE_WITH_NKEY_SEED");
        String subject = at(args,4,"test");

        try (NatsNKeyClient client = new NatsNKeyClient(url, seed)) {
            client.connect();
            System.out.printf("[CLIENT] Connected via NKey (public: %s)%n", client.getPublicKey());
            client.subscribe(subject);
            client.publish(subject, "hello from NKey client");
            System.out.printf("[CLIENT] Published to '%s'%n", subject);
            Thread.sleep(500);
        } catch (AuthenticationException e) {
            System.err.println("[CLIENT] NKey auth failed — check NKey is in auth-nkeys bucket"); System.exit(1);
        }
    }

    private static String at(String[] a,int i,String d){return a!=null&&a.length>i?a[i]:d;}
}
